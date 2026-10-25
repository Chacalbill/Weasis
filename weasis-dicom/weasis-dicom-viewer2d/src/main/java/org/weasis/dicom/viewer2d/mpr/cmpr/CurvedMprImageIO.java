/*
 * Copyright (c) 2024 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.viewer2d.mpr.cmpr;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.SpecificCharacterSet;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.img.DicomMetaData;
import org.dcm4che3.img.stream.ImageDescriptor;
import org.dcm4che3.util.UIDUtils;
import org.joml.Vector3d;
import org.opencv.core.Core.MinMaxLocResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.explorer.model.DataExplorerModel;
import org.weasis.core.api.media.data.Codec;
import org.weasis.core.api.media.data.FileCache;
import org.weasis.core.api.media.data.MediaElement;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.util.SoftHashMap;
import org.weasis.dicom.codec.DcmMediaReader;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.utils.DicomMediaUtils;
import org.weasis.dicom.viewer2d.mpr.Volume;
import org.weasis.opencv.data.ImageCV;
import org.weasis.opencv.data.PlanarImage;

/**
 * Image I/O handler for curved MPR panoramic images.
 *
 * <p>This class generates the "straightened" panoramic view by sampling the volume along the curved
 * path defined in CurvedMprAxis.
 */
public class CurvedMprImageIO implements DcmMediaReader {
  private static final Logger LOGGER = LoggerFactory.getLogger(CurvedMprImageIO.class);

  private static final String MIME_TYPE = "image/cmpr";

  private static final SoftHashMap<CurvedMprImageIO, DicomMetaData> HEADER_CACHE =
      new SoftHashMap<>();

  private final FileCache fileCache;
  private final HashMap<TagW, Object> tags;
  private final URI uri;
  private final CurvedMprAxis axis;
  private final Volume<?, ?> volume;
  private Attributes attributes;

  public CurvedMprImageIO(CurvedMprAxis axis) {
    this.axis = Objects.requireNonNull(axis);
    this.volume = axis.getVolume();
    this.fileCache = new FileCache(this);
    this.tags = new HashMap<>();
    try {
      this.uri = new URI("data:" + MIME_TYPE);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e);
    }
    updateIdentityTags();
  }

  public void setBaseAttributes(Attributes attributes) {
    this.attributes = attributes;
  }

  @Override
  public PlanarImage getImageFragment(MediaElement media) throws Exception {
    return generatePanoramicImage();
  }

  /**
   * Generate the panoramic image using Curved Planar Reformation (CPR).
   *
   * <p>The curve is drawn on one of the MPR planes (axial, coronal, sagittal or an oblique rotation
   * of them); the reconstruction is built relative to that plane rather than assuming axial:
   *
   * <ul>
   *   <li>The curve lies in the source plane; the vertical (height) axis marches along the plane
   *       normal — the direction orthogonal to the plane the polyline was drawn on
   *   <li>At each curve point a single voxel on the curve is sampled (a thin curved slice, like the
   *       cross-section slices and the MPR views) so the pixel values match the source exactly
   *   <li>Output X = arc-length position along the curve
   *   <li>Output Y = position along the plane normal, centered on the curve
   * </ul>
   */
  private PlanarImage generatePanoramicImage() {
    List<Vector3d> curvePoints = axis.getCurvePoints3D();
    if (curvePoints.size() < 2) {
      return null;
    }

    double stepMm = axis.getStepMm();
    double sliceSizeMm = axis.getWidthMm();
    double pixelMm = volume.getMinPixelRatio();
    Vector3d voxelRatio = volume.getVoxelRatio();

    LOGGER.info("=== generatePanoramicImage CPR ===");
    LOGGER.info(
        "curvePoints: {}, stepMm: {}, widthMm: {}, pixelMm: {}",
        curvePoints.size(),
        stepMm,
        sliceSizeMm,
        pixelMm);
    LOGGER.info(
        "volume size: {}x{}x{}, voxelRatio: ({},{},{})",
        volume.getSize().x,
        volume.getSize().y,
        volume.getSize().z,
        voxelRatio.x,
        voxelRatio.y,
        voxelRatio.z);

    CurveSampler.Sampling sampling =
        CurveSampler.sample(curvePoints, axis.getPlaneNormal(), stepMm, pixelMm);
    List<Vector3d> smoothedPoints = sampling.smoothedPoints();
    List<Vector3d> sampledPoints = sampling.sampledPoints();
    List<Vector3d> perpDirs = sampling.perpDirections();
    if (sampledPoints.isEmpty()) {
      return null;
    }

    // Vertical extent: slab height along the plane normal (in mm)
    int heightPx = (int) Math.round(sliceSizeMm / pixelMm);
    if (heightPx < 1) heightPx = 1;

    int widthPx = sampledPoints.size();

    // Length along the smoothed curve: points are in voxel-ratio-scaled space (1 unit = pixelMm)
    double lengthVoxels = 0;
    for (int i = 1; i < smoothedPoints.size(); i++) {
      lengthVoxels += smoothedPoints.get(i).distance(smoothedPoints.get(i - 1));
    }
    axis.setCenterlineLengthMm(lengthVoxels * pixelMm);
    axis.setSampledPoints(sampledPoints);

    if (axis.getReformationType() == CurvedMprAxis.ReformationType.STRETCHED) {
      return generateStretchedImage(sampledPoints, pixelMm, voxelRatio);
    }
    axis.setStretchedFrame(null);

    if (CurvedMprAxis.DEBUG_DRAW) {
      axis.setDebugData(
          new CurvedMprAxis.DebugCurveData(
              curvePoints, smoothedPoints, sampledPoints, perpDirs, pixelMm));
    }

    LOGGER.info("Output: {}x{} px, height={}mm", widthPx, heightPx, sliceSizeMm);

    Vector3d normal = axis.getPlaneNormal();
    final List<Vector3d> rowDirs =
        axis.isVesselMode()
            ? CurveSampler.rowDirections(sampledPoints, normal, axis.getAngleDeg())
            : null;

    // Diagnostic logging
    int midI = widthPx / 2;
    Vector3d midPt = sampledPoints.get(midI);
    Vector3d midPerp = perpDirs.get(midI);
    LOGGER.info(
        "Middle curve point[{}]: ({},{},{})",
        midI,
        String.format("%.1f", midPt.x),
        String.format("%.1f", midPt.y),
        String.format("%.1f", midPt.z));
    LOGGER.info(
        "Middle perp: ({},{},{})",
        String.format("%.3f", midPerp.x),
        String.format("%.3f", midPerp.y),
        String.format("%.3f", midPerp.z));

    // Column: a point along the curve; row: the offset along the plane normal
    int rows = heightPx;
    ImageCV dst =
        VolumeSampler.sample(
            volume,
            widthPx,
            heightPx,
            (column, row, voxel) -> {
              Vector3d curvePoint = sampledPoints.get(column);
              double offset = row - rows / 2.0;
              Vector3d dir = rowDirs == null ? normal : rowDirs.get(column);
              voxel.set(
                  (curvePoint.x + dir.x * offset) / voxelRatio.x,
                  (curvePoint.y + dir.y * offset) / voxelRatio.y,
                  (curvePoint.z + dir.z * offset) / voxelRatio.z);
            });

    LOGGER.info("Generated CPR panoramic image");

    setDicomTags(widthPx, heightPx, stepMm, pixelMm);
    return dst;
  }

  /**
   * Stretched reformation: a maximum-intensity slab of the volume seen along a viewing direction
   * (the coronal one by default, rotated around the vertical axis by the angle), cropped around the
   * vessel path. The vessel keeps its natural shape; the path and the A/B/C marks are drawn on top
   * using {@link CurvedMprAxis.StretchedFrame}. Pixels are square, so in-plane distances are real
   * (projected) distances; the vessel length is the "Vessel length" label.
   */
  private PlanarImage generateStretchedImage(
      List<Vector3d> points, double pixelMm, Vector3d voxelRatio) {
    CurvedMprAxis.ViewBasis base = axis.getViewBasis();
    Vector3d right = base == null ? new Vector3d(1, 0, 0) : new Vector3d(base.right());
    Vector3d down = base == null ? new Vector3d(0, 0, 1) : new Vector3d(base.down());
    Vector3d normal = base == null ? new Vector3d(0, 1, 0) : new Vector3d(base.normal());

    // Rotate the viewing direction around the vertical (down) axis
    double angle = Math.toRadians(axis.getAngleDeg());
    double cos = Math.cos(angle);
    double sin = Math.sin(angle);
    Vector3d rightR =
        new Vector3d(right).mul(cos).fma(sin, new Vector3d(down).cross(right)).normalize();
    Vector3d normalR =
        new Vector3d(normal).mul(cos).fma(sin, new Vector3d(down).cross(normal)).normalize();

    // Extent of the path on the image plane, plus a margin
    double minX = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE;
    double minY = Double.MAX_VALUE;
    double maxY = -Double.MAX_VALUE;
    double sumN = 0;
    for (Vector3d point : points) {
      double x = point.dot(rightR);
      double y = point.dot(down);
      minX = Math.min(minX, x);
      maxX = Math.max(maxX, x);
      minY = Math.min(minY, y);
      maxY = Math.max(maxY, y);
      sumN += point.dot(normalR);
    }
    double marginUnits = Math.max(20.0, axis.getWidthMm() / 2.0) / pixelMm;
    double extentX = Math.max(1.0, maxX - minX) + 2 * marginUnits;
    double extentY = Math.max(1.0, maxY - minY) + 2 * marginUnits;
    final double unitsPerPixel = Math.max(1.0, Math.max(extentX, extentY) / 2400.0);
    final int widthPx = Math.max(2, (int) Math.ceil(extentX / unitsPerPixel));
    final int heightPx = Math.max(2, (int) Math.ceil(extentY / unitsPerPixel));
    final Vector3d center =
        new Vector3d(rightR)
            .mul((minX + maxX) / 2.0)
            .fma((minY + maxY) / 2.0, down)
            .fma(sumN / points.size(), normalR);

    double mmPerPixel = unitsPerPixel * pixelMm;
    int slabSamples = Math.max(1, (int) Math.round(axis.getSlabMm() / mmPerPixel));
    Vector3d slabStep =
        new Vector3d(
            normalR.x * unitsPerPixel / voxelRatio.x,
            normalR.y * unitsPerPixel / voxelRatio.y,
            normalR.z * unitsPerPixel / voxelRatio.z);

    ImageCV dst =
        VolumeSampler.sample(
            volume,
            widthPx,
            heightPx,
            (column, row, voxel) -> {
              double dx = (column - widthPx / 2.0) * unitsPerPixel;
              double dy = (row - heightPx / 2.0) * unitsPerPixel;
              voxel.set(
                  (center.x + rightR.x * dx + down.x * dy) / voxelRatio.x,
                  (center.y + rightR.y * dx + down.y * dy) / voxelRatio.y,
                  (center.z + rightR.z * dx + down.z * dy) / voxelRatio.z);
            },
            slabSamples,
            slabStep);

    axis.setStretchedFrame(
        new CurvedMprAxis.StretchedFrame(
            center,
            new CurvedMprAxis.ViewBasis(rightR, new Vector3d(down), normalR),
            widthPx,
            heightPx,
            unitsPerPixel));
    setDicomTags(widthPx, heightPx, mmPerPixel, mmPerPixel);
    return dst;
  }

  /**
   * Refresh the per-image identity tags. PixelSpacing is written only when the sampling step equals
   * the output row spacing (square pixels, the default): then a horizontal measurement follows the
   * vessel path (X is arc length) and a vertical one is a true perpendicular distance, so the
   * standard length tools report calibrated values. Oblique measurements are not reliable on a
   * straightened image. With a custom step the pixels are not square and PixelSpacing is omitted,
   * so measurements fall back to pixels.
   */
  public void updateIdentityTags() {
    tags.putIfAbsent(TagD.get(Tag.SOPInstanceUID), UIDUtils.createUID());
    tags.putIfAbsent(TagD.get(Tag.InstanceNumber), 1);
  }

  private void setDicomTags(int widthPx, int heightPx, double stepMm, double pixelMm) {
    HEADER_CACHE.remove(this);
    tags.put(TagD.get(Tag.Columns), widthPx);
    tags.put(TagD.get(Tag.Rows), heightPx);
    if (pixelMm > 0 && Math.abs(stepMm - pixelMm) < 1e-6) {
      tags.put(TagD.get(Tag.PixelSpacing), new double[] {pixelMm, pixelMm});
    } else {
      tags.remove(TagD.get(Tag.PixelSpacing));
    }
    updateIdentityTags();
  }

  @Override
  public URI getUri() {
    return uri;
  }

  @Override
  public MediaElement getPreview() {
    return null;
  }

  @Override
  public boolean delegate(DataExplorerModel explorerModel) {
    return false;
  }

  @Override
  public DicomImageElement[] getMediaElement() {
    return null;
  }

  @Override
  public DicomSeries getMediaSeries() {
    return null;
  }

  @Override
  public int getMediaElementNumber() {
    return 1;
  }

  @Override
  public String getMediaFragmentMimeType() {
    return MIME_TYPE;
  }

  @Override
  public Map<TagW, Object> getMediaFragmentTags(Object key) {
    return tags;
  }

  @Override
  public void close() {
    HEADER_CACHE.remove(this);
  }

  @Override
  public Codec getCodec() {
    return null;
  }

  @Override
  public String[] getReaderDescription() {
    return new String[] {"Curved MPR Image Decoder"};
  }

  @Override
  public Object getTagValue(TagW tag) {
    return tag == null ? null : tags.get(tag);
  }

  @Override
  public void setTag(TagW tag, Object value) {
    DicomMediaUtils.setTag(tags, tag, value);
  }

  @Override
  public void setTagNoNull(TagW tag, Object value) {
    if (value != null) {
      setTag(tag, value);
    }
  }

  @Override
  public boolean containTagKey(TagW tag) {
    return tags.containsKey(tag);
  }

  @Override
  public Iterator<Entry<TagW, Object>> getTagEntrySetIterator() {
    return tags.entrySet().iterator();
  }

  public void copyTags(TagW[] tagList, MediaElement media, boolean allowNullValue) {
    if (tagList != null && media != null) {
      for (TagW tag : tagList) {
        Object value = media.getTagValue(tag);
        if (allowNullValue || value != null) {
          tags.put(tag, value);
        }
      }
    }
  }

  @Override
  public void replaceURI(URI uri) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Attributes getDicomObject() {
    return buildAttributes(tags, attributes);
  }

  static Attributes buildAttributes(HashMap<TagW, Object> tags, Attributes attributes) {
    Attributes dcm = new Attributes(tags.size() + (attributes != null ? attributes.size() : 0));
    if (attributes != null) {
      SpecificCharacterSet cs = attributes.getSpecificCharacterSet();
      dcm.setSpecificCharacterSet(cs.toCodes());
      dcm.addAll(attributes);
    }
    DicomMediaUtils.fillAttributes(tags, dcm);
    return dcm;
  }

  @Override
  public FileCache getFileCache() {
    return fileCache;
  }

  @Override
  public boolean buildFile(File output) {
    return false;
  }

  @Override
  public DicomMetaData getDicomMetaData() {
    return readMetaData();
  }

  @Override
  public boolean isEditableDicom() {
    return false;
  }

  @Override
  public void writeMetaData(MediaSeriesGroup group) {
    DcmMediaReader.super.writeMetaData(group);
  }

  private synchronized DicomMetaData readMetaData() {
    DicomMetaData header = HEADER_CACHE.get(this);
    if (header != null) {
      return header;
    }
    Attributes dcm = getDicomObject();
    header = new DicomMetaData(dcm, UID.ImplicitVRLittleEndian);
    ImageDescriptor desc = header.getImageDescriptor();
    if (desc != null) {
      MinMaxLocResult minMax = new MinMaxLocResult();
      minMax.minVal = volume.getMinimum().doubleValue();
      minMax.maxVal = volume.getMaximum().doubleValue();
      desc.setMinMaxPixelValue(0, minMax);
    }
    HEADER_CACHE.put(this, header);
    return header;
  }
}
