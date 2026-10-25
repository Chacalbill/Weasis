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
import java.util.List;
import java.util.Optional;
import org.joml.Vector3d;
import org.weasis.dicom.viewer2d.mpr.Volume;

/**
 * Computes the centerline of a contrast-filled vessel in a {@link Volume} from a few points
 * clicked inside the lumen. Points are exchanged in the voxel-ratio-scaled space used by {@link
 * CurvedMprAxis}, so the result can be fed directly to the curved MPR.
 */
public final class VesselCenterline {

  private VesselCenterline() {}

  /**
   * @param seedsScaled the clicked points (start, optional waypoints, end) in voxel-ratio-scaled
   *     coordinates
   * @return the centerline in voxel-ratio-scaled coordinates, or empty when the vessel could not
   *     be traced between the seeds (not connected, or seeds outside the contrast-filled lumen)
   */
  public static Optional<List<Vector3d>> trace(Volume<?, ?> volume, List<Vector3d> seedsScaled) {
    if (volume == null || seedsScaled == null || seedsScaled.size() < 2) {
      return Optional.empty();
    }
    Vector3d ratio = volume.getVoxelRatio();
    List<double[]> seedsVoxel = new ArrayList<>(seedsScaled.size());
    for (Vector3d s : seedsScaled) {
      seedsVoxel.add(new double[] {s.x / ratio.x, s.y / ratio.y, s.z / ratio.z});
    }

    Vector3d pixelRatio = volume.getPixelRatio();
    double[] spacing = {pixelRatio.x, pixelRatio.y, pixelRatio.z};
    var size = volume.getSize();
    CenterlineTracer.VoxelGrid grid =
        new CenterlineTracer.VoxelGrid() {
          @Override
          public int sizeX() {
            return size.x;
          }

          @Override
          public int sizeY() {
            return size.y;
          }

          @Override
          public int sizeZ() {
            return size.z;
          }

          @Override
          public double[] spacingMm() {
            return spacing;
          }

          @Override
          public double value(int x, int y, int z) {
            return volume.getNearestDouble(x, y, z, 0);
          }
        };

    return CenterlineTracer.trace(grid, seedsVoxel, CenterlineTracer.Params.defaults())
        .map(
            points -> {
              List<Vector3d> scaled = new ArrayList<>(points.size());
              for (double[] p : points) {
                scaled.add(new Vector3d(p[0] * ratio.x, p[1] * ratio.y, p[2] * ratio.z));
              }
              return scaled;
            });
  }
}
