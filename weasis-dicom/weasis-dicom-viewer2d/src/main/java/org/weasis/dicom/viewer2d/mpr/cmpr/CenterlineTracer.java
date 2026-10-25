/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.viewer2d.mpr.cmpr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Traces the centerline of a contrast-filled vessel between user-clicked seed points.
 *
 * <p>The algorithm works on a cropped region around the seeds:
 *
 * <ol>
 *   <li>The lumen intensity is estimated from the neighbourhood of the seeds (median and robust
 *       spread), and a lumen mask is obtained by thresholding around that value.
 *   <li>A distance map (in mm) from every lumen voxel to the nearest non-lumen voxel is computed, so
 *       that the vessel axis is where the distance is largest.
 *   <li>A minimal-cost path (Dijkstra, 26-connectivity) is searched between consecutive seeds, with
 *       a voxel cost that is very low near the axis and very high near the wall.
 *   <li>The path is smoothed and decimated.
 * </ol>
 *
 * This class is independent from Weasis types: voxel coordinates are plain {@code double[3]} in
 * voxel index space, and the data comes from a {@link VoxelGrid}.
 */
public final class CenterlineTracer {

  /** Read-only access to a 3D scalar volume. */
  public interface VoxelGrid {
    int sizeX();

    int sizeY();

    int sizeZ();

    /** Voxel spacing in mm along x, y and z. */
    double[] spacingMm();

    /** Value at an integer voxel, or NaN when outside. */
    double value(int x, int y, int z);
  }

  /** Tunable parameters. */
  public record Params(
      double marginMm,
      double minToleranceHu,
      double toleranceSigma,
      double upperToleranceFactor,
      double snapRadiusMm,
      double outputSpacingMm,
      int smoothingPasses,
      long maxRoiVoxels) {

    public static Params defaults() {
      return new Params(25.0, 100.0, 3.0, 2.0, 5.0, 3.0, 4, 40_000_000L);
    }
  }

  private static final int[][] NEIGHBORS = buildNeighbors();

  private CenterlineTracer() {}

  /**
   * @param seeds seed points (at least 2) in voxel index coordinates, in the order they must be
   *     visited
   * @return the centerline in voxel index coordinates, or empty when no path could be found
   */
  public static Optional<List<double[]>> trace(VoxelGrid grid, List<double[]> seeds, Params p) {
    if (grid == null || seeds == null || seeds.size() < 2) {
      return Optional.empty();
    }
    double[] sp = grid.spacingMm();
    int nx = grid.sizeX();
    int ny = grid.sizeY();
    int nz = grid.sizeZ();

    // Crop region around the seeds
    int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
    int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
    for (double[] s : seeds) {
      for (int a = 0; a < 3; a++) {
        int v = (int) Math.round(s[a]);
        lo[a] = Math.min(lo[a], v);
        hi[a] = Math.max(hi[a], v);
      }
    }
    int[] size = {nx, ny, nz};
    for (int a = 0; a < 3; a++) {
      int margin = (int) Math.ceil(p.marginMm() / sp[a]);
      lo[a] = Math.max(0, lo[a] - margin);
      hi[a] = Math.min(size[a] - 1, hi[a] + margin);
      if (lo[a] > hi[a]) {
        return Optional.empty();
      }
    }
    int rx = hi[0] - lo[0] + 1;
    int ry = hi[1] - lo[1] + 1;
    int rz = hi[2] - lo[2] + 1;
    long total = (long) rx * ry * rz;
    if (total > p.maxRoiVoxels()) {
      return Optional.empty();
    }
    int n = (int) total;

    float[] values = new float[n];
    for (int z = 0; z < rz; z++) {
      for (int y = 0; y < ry; y++) {
        for (int x = 0; x < rx; x++) {
          values[(z * ry + y) * rx + x] = (float) grid.value(x + lo[0], y + lo[1], z + lo[2]);
        }
      }
    }

    // Seeds in ROI index space
    int[] seedIdx = new int[seeds.size()];
    for (int i = 0; i < seeds.size(); i++) {
      double[] s = seeds.get(i);
      int x = clamp((int) Math.round(s[0]) - lo[0], rx);
      int y = clamp((int) Math.round(s[1]) - lo[1], ry);
      int z = clamp((int) Math.round(s[2]) - lo[2], rz);
      seedIdx[i] = (z * ry + y) * rx + x;
    }

    // Lumen intensity from the seed neighbourhoods
    double[] stats = lumenStats(values, seedIdx, rx, ry, rz);
    if (stats == null) {
      return Optional.empty();
    }
    double median = stats[0];
    double tol = Math.max(p.minToleranceHu(), p.toleranceSigma() * stats[1]);
    double low = median - tol;
    double high = median + p.upperToleranceFactor() * tol;

    boolean[] mask = new boolean[n];
    for (int i = 0; i < n; i++) {
      float v = values[i];
      mask[i] = v >= low && v <= high; // NaN compares false
    }
    values = null;

    // Snap seeds that are not in the mask to the closest lumen voxel
    for (int i = 0; i < seedIdx.length; i++) {
      if (!mask[seedIdx[i]]) {
        int snapped = snapToMask(mask, seedIdx[i], rx, ry, rz, sp, p.snapRadiusMm());
        if (snapped < 0) {
          return Optional.empty();
        }
        seedIdx[i] = snapped;
      }
    }

    float[] dist = distanceToWall(mask, rx, ry, rz, sp);

    // Voxel cost: tiny on the axis, huge near the wall
    float[] cost = new float[n];
    for (int i = 0; i < n; i++) {
      if (mask[i]) {
        double d = dist[i] + 0.5;
        cost[i] = (float) (1.0 / (d * d));
      } else {
        cost[i] = Float.NaN;
      }
    }
    dist = null;

    // Minimal-cost path through the seeds
    List<Integer> path = new ArrayList<>();
    float[] acc = new float[n];
    int[] prev = new int[n];
    for (int i = 0; i < seedIdx.length - 1; i++) {
      List<Integer> leg = shortestPath(cost, acc, prev, seedIdx[i], seedIdx[i + 1], rx, ry, rz, sp);
      if (leg.isEmpty()) {
        return Optional.empty();
      }
      if (!path.isEmpty()) {
        leg.removeFirst(); // joint already present
      }
      path.addAll(leg);
    }

    List<double[]> points = new ArrayList<>(path.size());
    for (int idx : path) {
      int x = idx % rx;
      int y = (idx / rx) % ry;
      int z = idx / (rx * ry);
      points.add(new double[] {x + lo[0], y + lo[1], z + lo[2]});
    }
    return Optional.of(smoothAndDecimate(points, sp, p));
  }

  // ---- lumen statistics -------------------------------------------------------------------

  private static double[] lumenStats(float[] values, int[] seedIdx, int rx, int ry, int rz) {
    List<Float> samples = new ArrayList<>();
    for (int idx : seedIdx) {
      int x0 = idx % rx;
      int y0 = (idx / rx) % ry;
      int z0 = idx / (rx * ry);
      for (int dz = -1; dz <= 1; dz++) {
        for (int dy = -1; dy <= 1; dy++) {
          for (int dx = -1; dx <= 1; dx++) {
            int x = x0 + dx;
            int y = y0 + dy;
            int z = z0 + dz;
            if (x >= 0 && x < rx && y >= 0 && y < ry && z >= 0 && z < rz) {
              float v = values[(z * ry + y) * rx + x];
              if (!Float.isNaN(v)) {
                samples.add(v);
              }
            }
          }
        }
      }
    }
    if (samples.isEmpty()) {
      return null;
    }
    double med = median(samples);
    List<Float> dev = new ArrayList<>(samples.size());
    for (float v : samples) {
      dev.add((float) Math.abs(v - med));
    }
    double sigma = 1.4826 * median(dev);
    return new double[] {med, sigma};
  }

  private static double median(List<Float> list) {
    float[] a = new float[list.size()];
    for (int i = 0; i < a.length; i++) {
      a[i] = list.get(i);
    }
    Arrays.sort(a);
    int m = a.length / 2;
    return a.length % 2 == 1 ? a[m] : 0.5 * (a[m - 1] + a[m]);
  }

  private static int snapToMask(
      boolean[] mask, int idx, int rx, int ry, int rz, double[] sp, double radiusMm) {
    int x0 = idx % rx;
    int y0 = (idx / rx) % ry;
    int z0 = idx / (rx * ry);
    int rxv = (int) Math.ceil(radiusMm / sp[0]);
    int ryv = (int) Math.ceil(radiusMm / sp[1]);
    int rzv = (int) Math.ceil(radiusMm / sp[2]);
    double best = Double.MAX_VALUE;
    int bestIdx = -1;
    for (int dz = -rzv; dz <= rzv; dz++) {
      for (int dy = -ryv; dy <= ryv; dy++) {
        for (int dx = -rxv; dx <= rxv; dx++) {
          int x = x0 + dx;
          int y = y0 + dy;
          int z = z0 + dz;
          if (x < 0 || x >= rx || y < 0 || y >= ry || z < 0 || z >= rz) {
            continue;
          }
          int j = (z * ry + y) * rx + x;
          if (!mask[j]) {
            continue;
          }
          double d = Math.sqrt(sq(dx * sp[0]) + sq(dy * sp[1]) + sq(dz * sp[2]));
          if (d <= radiusMm && d < best) {
            best = d;
            bestIdx = j;
          }
        }
      }
    }
    return bestIdx;
  }

  // ---- distance map -----------------------------------------------------------------------

  /** Distance (mm) from each lumen voxel to the closest non-lumen voxel (0 outside the lumen). */
  private static float[] distanceToWall(boolean[] mask, int rx, int ry, int rz, double[] sp) {
    int n = mask.length;
    float[] dist = new float[n];
    Arrays.fill(dist, Float.POSITIVE_INFINITY);
    LongHeap heap = new LongHeap(1 << 16);
    double[] stepLen = stepLengths(sp);
    for (int z = 0; z < rz; z++) {
      for (int y = 0; y < ry; y++) {
        for (int x = 0; x < rx; x++) {
          int i = (z * ry + y) * rx + x;
          if (mask[i]) {
            continue;
          }
          dist[i] = 0f;
          // Only wall voxels touching the lumen need to be pushed
          if (touchesMask(mask, x, y, z, rx, ry, rz)) {
            heap.push(key(0f, i));
          }
        }
      }
    }
    while (!heap.isEmpty()) {
      long k = heap.pop();
      int i = indexOf(k);
      float d = distOf(k);
      if (d > dist[i]) {
        continue;
      }
      int x = i % rx;
      int y = (i / rx) % ry;
      int z = i / (rx * ry);
      for (int ni = 0; ni < NEIGHBORS.length; ni++) {
        int[] o = NEIGHBORS[ni];
        int xx = x + o[0];
        int yy = y + o[1];
        int zz = z + o[2];
        if (xx < 0 || xx >= rx || yy < 0 || yy >= ry || zz < 0 || zz >= rz) {
          continue;
        }
        int j = (zz * ry + yy) * rx + xx;
        if (!mask[j]) {
          continue;
        }
        float nd = d + (float) stepLen[ni];
        if (nd < dist[j]) {
          dist[j] = nd;
          heap.push(key(nd, j));
        }
      }
    }
    // Lumen voxels never reached (no wall in the crop) keep a large but finite distance
    for (int i = 0; i < n; i++) {
      if (Float.isInfinite(dist[i])) {
        dist[i] = 1000f;
      }
    }
    return dist;
  }

  private static boolean touchesMask(
      boolean[] mask, int x, int y, int z, int rx, int ry, int rz) {
    for (int[] o : NEIGHBORS) {
      int xx = x + o[0];
      int yy = y + o[1];
      int zz = z + o[2];
      if (xx >= 0
          && xx < rx
          && yy >= 0
          && yy < ry
          && zz >= 0
          && zz < rz
          && mask[(zz * ry + yy) * rx + xx]) {
        return true;
      }
    }
    return false;
  }

  // ---- shortest path ----------------------------------------------------------------------

  private static List<Integer> shortestPath(
      float[] cost,
      float[] acc,
      int[] prev,
      int start,
      int goal,
      int rx,
      int ry,
      int rz,
      double[] sp) {
    Arrays.fill(acc, Float.POSITIVE_INFINITY);
    Arrays.fill(prev, -1);
    double[] stepLen = stepLengths(sp);
    LongHeap heap = new LongHeap(1 << 16);
    acc[start] = 0f;
    heap.push(key(0f, start));
    boolean found = false;
    while (!heap.isEmpty()) {
      long k = heap.pop();
      int i = indexOf(k);
      float d = distOf(k);
      if (d > acc[i]) {
        continue;
      }
      if (i == goal) {
        found = true;
        break;
      }
      int x = i % rx;
      int y = (i / rx) % ry;
      int z = i / (rx * ry);
      for (int ni = 0; ni < NEIGHBORS.length; ni++) {
        int[] o = NEIGHBORS[ni];
        int xx = x + o[0];
        int yy = y + o[1];
        int zz = z + o[2];
        if (xx < 0 || xx >= rx || yy < 0 || yy >= ry || zz < 0 || zz >= rz) {
          continue;
        }
        int j = (zz * ry + yy) * rx + xx;
        float cj = cost[j];
        if (Float.isNaN(cj)) {
          continue; // outside the lumen
        }
        float nd = d + (float) (stepLen[ni] * 0.5 * (cost[i] + cj));
        if (nd < acc[j]) {
          acc[j] = nd;
          prev[j] = i;
          heap.push(key(nd, j));
        }
      }
    }
    List<Integer> path = new ArrayList<>();
    if (!found) {
      return path;
    }
    for (int i = goal; i != -1; i = prev[i]) {
      path.add(i);
    }
    java.util.Collections.reverse(path);
    return path;
  }

  // ---- smoothing --------------------------------------------------------------------------

  private static List<double[]> smoothAndDecimate(List<double[]> pts, double[] sp, Params p) {
    int n = pts.size();
    double[][] cur = new double[n][];
    for (int i = 0; i < n; i++) {
      cur[i] = pts.get(i).clone();
    }
    if (n > 4) {
      for (int pass = 0; pass < p.smoothingPasses(); pass++) {
        double[][] next = new double[n][];
        next[0] = cur[0];
        next[n - 1] = cur[n - 1];
        for (int i = 1; i < n - 1; i++) {
          int a = Math.max(0, i - 2);
          int b = Math.min(n - 1, i + 2);
          double[] m = new double[3];
          for (int k = a; k <= b; k++) {
            for (int c = 0; c < 3; c++) {
              m[c] += cur[k][c];
            }
          }
          for (int c = 0; c < 3; c++) {
            m[c] /= (b - a + 1);
          }
          next[i] = m;
        }
        cur = next;
      }
    }
    List<double[]> out = new ArrayList<>();
    out.add(cur[0]);
    double acc = 0;
    for (int i = 1; i < n; i++) {
      acc += mmDistance(cur[i - 1], cur[i], sp);
      if (acc >= p.outputSpacingMm() && i < n - 1) {
        out.add(cur[i]);
        acc = 0;
      }
    }
    if (n > 1) {
      out.add(cur[n - 1]);
    }
    return out;
  }

  // ---- helpers ----------------------------------------------------------------------------

  private static double mmDistance(double[] a, double[] b, double[] sp) {
    return Math.sqrt(
        sq((a[0] - b[0]) * sp[0]) + sq((a[1] - b[1]) * sp[1]) + sq((a[2] - b[2]) * sp[2]));
  }

  private static double sq(double v) {
    return v * v;
  }

  private static int clamp(int v, int size) {
    return Math.max(0, Math.min(size - 1, v));
  }

  private static double[] stepLengths(double[] sp) {
    double[] len = new double[NEIGHBORS.length];
    for (int i = 0; i < NEIGHBORS.length; i++) {
      int[] o = NEIGHBORS[i];
      len[i] = Math.sqrt(sq(o[0] * sp[0]) + sq(o[1] * sp[1]) + sq(o[2] * sp[2]));
    }
    return len;
  }

  private static int[][] buildNeighbors() {
    List<int[]> list = new ArrayList<>();
    for (int dz = -1; dz <= 1; dz++) {
      for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
          if (dx != 0 || dy != 0 || dz != 0) {
            list.add(new int[] {dx, dy, dz});
          }
        }
      }
    }
    return list.toArray(new int[0][]);
  }

  // Heap keys: non-negative float bits in the high 32 bits (ordering preserved), index in the low
  private static long key(float d, int idx) {
    return ((long) Float.floatToIntBits(d) << 32) | (idx & 0xFFFFFFFFL);
  }

  private static int indexOf(long key) {
    return (int) key;
  }

  private static float distOf(long key) {
    return Float.intBitsToFloat((int) (key >>> 32));
  }

  /** Minimal primitive binary min-heap of longs. */
  private static final class LongHeap {
    private long[] data;
    private int size;

    LongHeap(int capacity) {
      data = new long[capacity];
    }

    boolean isEmpty() {
      return size == 0;
    }

    void push(long v) {
      if (size == data.length) {
        data = Arrays.copyOf(data, size * 2);
      }
      int i = size++;
      while (i > 0) {
        int parent = (i - 1) >>> 1;
        if (data[parent] <= v) {
          break;
        }
        data[i] = data[parent];
        i = parent;
      }
      data[i] = v;
    }

    long pop() {
      long top = data[0];
      long last = data[--size];
      if (size > 0) {
        int i = 0;
        while (true) {
          int child = 2 * i + 1;
          if (child >= size) {
            break;
          }
          if (child + 1 < size && data[child + 1] < data[child]) {
            child++;
          }
          if (data[child] >= last) {
            break;
          }
          data[i] = data[child];
          i = child;
        }
        data[i] = last;
      }
      return top;
    }
  }
}
