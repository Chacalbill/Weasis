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

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import org.joml.Vector3d;
import org.weasis.dicom.viewer2d.mpr.Volume;

/**
 * Small 8-bit cross-sections of the vessel (perpendicular to it) at a few columns of the curved
 * reformation, shown next to it as the A / B / C strip. The gray window is derived from the
 * sampled values (not from the window of the other views), so it is a preview only.
 */
final class CrossSectionPreview {

  private CrossSectionPreview() {}

  /**
   * @param columns indices into {@link CurvedMprAxis#getSampledPoints()} of the sections to render
   * @param sectionMm side of the square sections in mm
   * @return one image per column, or {@code null} when the curve is not available
   */
  static BufferedImage[] render(CurvedMprAxis axis, int[] columns, double sectionMm) {
    List<Vector3d> points = axis.getSampledPoints();
    int n = points.size();
    Volume<?, ?> volume = axis.getVolume();
    if (n < 2 || volume == null || columns.length == 0) {
      return null;
    }
    double pixelMm = volume.getMinPixelRatio();
    Vector3d ratio = volume.getVoxelRatio();
    List<Vector3d> rowDirs =
        CurveSampler.rowDirections(
            points, axis.getPlaneNormal(), axis.isVesselMode() ? axis.getAngleDeg() : 0.0);
    int size = Math.max(16, Math.min(160, (int) Math.round(sectionMm / pixelMm)));

    double[][] data = new double[columns.length][size * size];
    for (int ci = 0; ci < columns.length; ci++) {
      int c = Math.clamp(columns[ci], 0, n - 1);
      Vector3d p = points.get(c);
      Vector3d tangent = new Vector3d(points.get(Math.min(c + 1, n - 1))).sub(points.get(Math.max(c - 1, 0)));
      if (tangent.lengthSquared() < 1e-12) {
        tangent.set(0, 0, 1);
      } else {
        tangent.normalize();
      }
      Vector3d r = rowDirs.get(c);
      Vector3d b = new Vector3d(tangent).cross(r);
      if (b.lengthSquared() < 1e-12) {
        b.set(1, 0, 0);
      } else {
        b.normalize();
      }
      for (int y = 0; y < size; y++) {
        for (int x = 0; x < size; x++) {
          double dx = x - size / 2.0;
          double dy = y - size / 2.0;
          data[ci][y * size + x] =
              volume.getInterpolatedDouble(
                  (p.x + b.x * dx + r.x * dy) / ratio.x,
                  (p.y + b.y * dx + r.y * dy) / ratio.y,
                  (p.z + b.z * dx + r.z * dy) / ratio.z,
                  0);
        }
      }
    }

    // Window: robust percentiles of all sections, kept close to the value at the vessel centre
    int centerSection = Math.min(1, columns.length - 1);
    double centerValue = centerMean(data[centerSection], size);
    double[] all =
        Arrays.stream(data).flatMapToDouble(Arrays::stream).filter(v -> !Double.isNaN(v)).sorted().toArray();
    double low = 0;
    double high = 1;
    if (all.length > 0) {
      low = all[(int) (all.length * 0.02)];
      high = all[Math.min(all.length - 1, (int) (all.length * 0.995))];
      if (!Double.isNaN(centerValue)) {
        low = Math.max(low, centerValue - 400);
        high = Math.min(high, centerValue + 400);
      }
      if (high - low < 1) {
        high = low + 1;
      }
    }

    BufferedImage[] images = new BufferedImage[columns.length];
    for (int ci = 0; ci < columns.length; ci++) {
      BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_GRAY);
      for (int y = 0; y < size; y++) {
        for (int x = 0; x < size; x++) {
          double v = data[ci][y * size + x];
          int gray = Double.isNaN(v) ? 0 : (int) Math.round(255.0 * (v - low) / (high - low));
          img.getRaster().setSample(x, y, 0, Math.clamp(gray, 0, 255));
        }
      }
      images[ci] = img;
    }
    return images;
  }

  private static double centerMean(double[] section, int size) {
    double sum = 0;
    int count = 0;
    int c = size / 2;
    for (int y = c - 2; y <= c + 2; y++) {
      for (int x = c - 2; x <= c + 2; x++) {
        double v = section[y * size + x];
        if (!Double.isNaN(v)) {
          sum += v;
          count++;
        }
      }
    }
    return count == 0 ? Double.NaN : sum / count;
  }
}
