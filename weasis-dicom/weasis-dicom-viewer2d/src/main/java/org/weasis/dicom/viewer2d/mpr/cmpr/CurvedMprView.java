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

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GridBagConstraints;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import net.miginfocom.swing.MigLayout;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.image.util.Unit;
import org.weasis.core.api.util.ResourceUtil;
import org.weasis.core.api.util.ResourceUtil.OtherIcon;
import org.weasis.core.ui.editor.image.ImageViewerEventManager;
import org.weasis.core.ui.editor.image.ViewButton;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.viewer2d.View2d;
import org.weasis.dicom.viewer2d.mpr.MprAxis;
import org.weasis.dicom.viewer2d.mpr.MprContainer;
import org.weasis.dicom.viewer2d.mpr.MprView;

/**
 * 2D view displaying a curved-MPR panoramic image inside a regular {@code MprContainer}.
 *
 * <p>The view extends {@link View2d} so it inherits the standard WL/LUT/zoom/pan controls; only the
 * curve-specific settings popup (height + sampling step sliders) is added.
 */
public class CurvedMprView extends View2d {

  private CurvedMprAxis curvedMprAxis;

  public CurvedMprView(ImageViewerEventManager<DicomImageElement> eventManager) {
    super(eventManager);
    getViewButtons().clear();
    ViewButton settings =
        new ViewButton(
            this::showPanoramicSettingsPopup,
            ResourceUtil.getIcon(OtherIcon.VIEW_SETTING).derive(24, 24),
            "Panoramic settings"); // NON-NLS
    settings.setVisible(true);
    settings.setPosition(GridBagConstraints.NORTHEAST);
    getViewButtons().add(settings);
  }

  public CurvedMprAxis getCurvedMprAxis() {
    return curvedMprAxis;
  }

  /**
   * Bind a curved-MPR axis to this view and display its image. Creates a synthetic {@link
   * DicomSeries} wrapper because {@code InfoLayer} reads its data through the series. The previous
   * axis (if any) is disposed so its polyline listener and cached image are released.
   */
  public void setCurvedMprAxis(CurvedMprAxis axis) {
    if (this.curvedMprAxis != null && this.curvedMprAxis != axis) {
      this.curvedMprAxis.dispose();
    }
    this.curvedMprAxis = axis;
    if (axis == null) {
      return;
    }
    axis.setView(this);
    applyViewBasis(axis);

    DicomImageElement img = axis.getImageElement();
    if (img != null) {
      DicomSeries series = new DicomSeries("curved-mpr-" + System.currentTimeMillis());
      series.addMedia(img);
      setSeries(series, img);
    }
  }

  /** The stretched view looks along the same direction as the coronal MPR plane. */
  private void applyViewBasis(CurvedMprAxis axis) {
    MprContainer container =
        (MprContainer) SwingUtilities.getAncestorOfClass(MprContainer.class, this);
    if (container == null || container.getMprController() == null) {
      return;
    }
    MprAxis coronal = container.getMprController().getMprAxis(MprView.Plane.CORONAL);
    if (coronal == null || coronal.getTransformation() == null) {
      return;
    }
    Matrix4d t = coronal.getTransformation();
    Vector3d right = t.transformDirection(new Vector3d(1, 0, 0)).normalize();
    Vector3d down = t.transformDirection(new Vector3d(0, 1, 0)).normalize();
    Vector3d normal = t.transformDirection(new Vector3d(0, 0, 1)).normalize();
    axis.setViewBasis(new CurvedMprAxis.ViewBasis(right, down, normal));
  }

  /** Draws the vessel length (along the curve) at the top centre of the view. */
  @Override
  protected void drawOnTop(Graphics2D g2d) {
    super.drawOnTop(g2d);
    CurvedMprAxis axis = curvedMprAxis;
    if (axis == null) {
      return;
    }
    double lengthMm = axis.getCenterlineLengthMm();
    if (lengthMm <= 0) {
      return;
    }
    String text = String.format("Vessel length: %.2f cm", lengthMm / 10.0); // NON-NLS
    Font oldFont = g2d.getFont();
    Color oldColor = g2d.getColor();
    g2d.setFont(oldFont.deriveFont(Font.BOLD, oldFont.getSize2D() + 2f));
    FontMetrics fm = g2d.getFontMetrics();
    int x = (getWidth() - fm.stringWidth(text)) / 2;
    int y = fm.getHeight() + 6;
    g2d.setColor(Color.BLACK);
    g2d.drawString(text, x + 1, y + 1);
    g2d.setColor(Color.YELLOW);
    g2d.drawString(text, x, y);
    g2d.setFont(oldFont);
    g2d.setColor(oldColor);
    drawCursor(g2d, axis);
  }

  private static final double SECTION_SPACING_MM = 5.0;

  private BufferedImage[] sections;
  private Object sectionsKey;

  /**
   * Draws the A/B/C marks (B is the position marker, A and C are a few mm before and after) and
   * the strip of cross-sections. On the stretched view the vessel path is also drawn.
   */
  private void drawCursor(Graphics2D g2d, CurvedMprAxis axis) {
    List<Vector3d> points = axis.getSampledPoints();
    int column = axis.getCursorColumn();
    if (column < 0 || column >= points.size()) {
      return;
    }
    int spacing = (int) Math.max(1, Math.round(SECTION_SPACING_MM / Math.max(1e-6, axis.getStepMm())));
    int last = points.size() - 1;
    int[] columns = {Math.max(0, column - spacing), column, Math.min(last, column + spacing)};
    String[] names = {"A", "B", "C"}; // NON-NLS
    double pixelMm = sanitizePixelMm(axis.getVolume().getMinPixelRatio());

    java.awt.Stroke oldStroke = g2d.getStroke();
    Color oldColor = g2d.getColor();
    Font oldFont = g2d.getFont();
    g2d.setFont(oldFont.deriveFont(Font.BOLD));
    CurvedMprAxis.StretchedFrame frame = axis.getStretchedFrame();
    if (frame == null) {
      double rows = Math.max(1, Math.round(axis.getWidthMm() / pixelMm));
      for (int i = 0; i < 3; i++) {
        java.awt.Point top = getMouseCoordinatesFromImage(columns[i] + 0.5, 0);
        java.awt.Point bottom = getMouseCoordinatesFromImage(columns[i] + 0.5, rows);
        g2d.setColor(Color.YELLOW);
        g2d.setStroke(new java.awt.BasicStroke(i == 1 ? 2f : 1f));
        g2d.drawLine(top.x, top.y, bottom.x, bottom.y);
        g2d.drawString(names[i], top.x + 4, top.y + 14);
      }
    } else {
      // The vessel path on the stretched image
      g2d.setColor(new Color(0, 220, 0, 200));
      g2d.setStroke(new java.awt.BasicStroke(1.5f));
      java.awt.Point prev = null;
      int step = Math.max(1, points.size() / 600);
      for (int i = 0; i < points.size(); i += step) {
        java.awt.geom.Point2D q = frame.project(points.get(i));
        java.awt.Point pt = getMouseCoordinatesFromImage(q.getX(), q.getY());
        if (prev != null) {
          g2d.drawLine(prev.x, prev.y, pt.x, pt.y);
        }
        prev = pt;
      }
      // Marks perpendicular to the path
      double halfLengthPx = 12.0 / (frame.unitsPerPixel() * pixelMm);
      for (int i = 0; i < 3; i++) {
        int c = columns[i];
        java.awt.geom.Point2D p0 = frame.project(points.get(c));
        java.awt.geom.Point2D pa = frame.project(points.get(Math.max(0, c - 1)));
        java.awt.geom.Point2D pb = frame.project(points.get(Math.min(last, c + 1)));
        double tx = pb.getX() - pa.getX();
        double ty = pb.getY() - pa.getY();
        double len = Math.hypot(tx, ty);
        if (len < 1e-9) {
          tx = 1;
          ty = 0;
          len = 1;
        }
        double nx = -ty / len * halfLengthPx;
        double ny = tx / len * halfLengthPx;
        java.awt.Point a = getMouseCoordinatesFromImage(p0.getX() + nx, p0.getY() + ny);
        java.awt.Point b = getMouseCoordinatesFromImage(p0.getX() - nx, p0.getY() - ny);
        g2d.setColor(Color.YELLOW);
        g2d.setStroke(new java.awt.BasicStroke(i == 1 ? 2f : 1f));
        g2d.drawLine(a.x, a.y, b.x, b.y);
        g2d.drawString(names[i], a.x + 4, a.y - 2);
      }
    }
    g2d.setFont(oldFont);
    g2d.setStroke(oldStroke);
    g2d.setColor(oldColor);
    drawSections(g2d, axis, columns, names);
  }

  /** Strip of the A/B/C cross-sections along the right edge of the view. */
  private void drawSections(Graphics2D g2d, CurvedMprAxis axis, int[] columns, String[] names) {
    Object key =
        List.of(
            columns[0],
            columns[1],
            columns[2],
            System.identityHashCode(axis.getSampledPoints()),
            axis.getAngleDeg(),
            axis.getWidthMm(),
            axis.isVesselMode());
    if (!key.equals(sectionsKey)) {
      sections = CrossSectionPreview.render(axis, columns, Math.min(60.0, axis.getWidthMm()));
      sectionsKey = key;
    }
    BufferedImage[] images = sections;
    if (images == null || images.length < 3) {
      return;
    }
    int gap = 6;
    int size = Math.max(60, Math.min(Math.min(getWidth() / 5, 220), (getHeight() - 60) / 3 - gap));
    int x = getWidth() - size - 8;
    int y = 40;
    Object oldHint = g2d.getRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION);
    Color oldColor = g2d.getColor();
    g2d.setRenderingHint(
        java.awt.RenderingHints.KEY_INTERPOLATION,
        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    for (int i = 0; i < 3; i++) {
      g2d.drawImage(images[i], x, y, size, size, null);
      g2d.setColor(i == 1 ? Color.YELLOW : Color.GRAY);
      g2d.drawRect(x, y, size, size);
      g2d.setColor(Color.YELLOW);
      g2d.drawString(names[i], x + 4, y + 14);
      g2d.drawLine(x + size / 2 - 4, y + size / 2, x + size / 2 + 4, y + size / 2);
      g2d.drawLine(x + size / 2, y + size / 2 - 4, x + size / 2, y + size / 2 + 4);
      y += size + gap;
    }
    if (oldHint != null) {
      g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, oldHint);
    }
    g2d.setColor(oldColor);
  }

  /**
   * Move the position marker to a panoramic column: the yellow line follows it and the crosshair of
   * the three MPR planes is moved to the same point of the vessel (so the planes show it).
   */
  public void moveCursorToColumn(int column) {
    CurvedMprAxis axis = curvedMprAxis;
    if (axis == null || axis.getSampledPoints().isEmpty()) {
      return;
    }
    axis.setCursorColumn(Math.clamp(column, 0, axis.getSampledPoints().size() - 1));
    Vector3d point = axis.getCursorPoint();
    MprContainer container =
        (MprContainer) SwingUtilities.getAncestorOfClass(MprContainer.class, this);
    if (point != null && container != null && container.getMprController() != null) {
      Vector3d ratio = axis.getVolume().getVoxelRatio();
      Vector3i size = axis.getVolume().getSize();
      container
          .getMprController()
          .setCrossHairAtVoxel(
              new Vector3i(
                  Math.clamp(Math.round(point.x / ratio.x), 0, size.x - 1),
                  Math.clamp(Math.round(point.y / ratio.y), 0, size.y - 1),
                  Math.clamp(Math.round(point.z / ratio.z), 0, size.z - 1)));
    }
    repaint();
  }

  @Override
  public void disposeView() {
    if (curvedMprAxis != null) {
      curvedMprAxis.dispose();
      curvedMprAxis = null;
    }
    super.disposeView();
  }

  /** Re-render the panoramic image after the axis parameters changed. */
  public void refreshCurvedImage() {
    if (curvedMprAxis == null) {
      return;
    }
    DicomImageElement img = curvedMprAxis.getImageElement();
    if (img == null) {
      return;
    }
    getImageLayer().setImage(null, null);
    img.removeImageFromCache();
    setImage(img);
    repaint();
  }

  /**
   * Show a popup with live sliders for the two panoramic generation parameters:
   *
   * <ul>
   *   <li><b>Height</b> — slab vertical extent ({@code widthMm} on the axis).
   *   <li><b>Step</b> — sampling distance along the curve ({@code stepMm} on the axis); changing
   *       this rescales the panoramic horizontally and the cross-section sample count.
   * </ul>
   *
   * Each slider regenerates the panoramic only when released (not on every tick) so dragging stays
   * responsive on large volumes. Ranges and scales are clamped so the current axis values always
   * sit inside the slider — works for tall volumes and for uncalibrated (pixel-only) data.
   */
  public void showPanoramicSettingsPopup(Component invoker, int x, int y) {
    if (curvedMprAxis == null) {
      return;
    }
    double pixelMm = sanitizePixelMm(curvedMprAxis.getVolume().getMinPixelRatio());
    String unitLabel = unitAbbreviation();

    JPanel panel =
        new JPanel(
            new MigLayout(
                "fillx, ins 6lp, wrap 1", "[grow, fill]", "[]2lp[]8lp[]2lp[]8lp[]2lp[]8lp[]2lp[]8lp[]2lp[]8lp[]2lp[]8lp[]")); // NON-NLS

    List<Runnable> resetActions = new ArrayList<>(2);
    addHeightSlider(panel, pixelMm, unitLabel, resetActions);
    addStepSlider(panel, pixelMm, unitLabel, resetActions);
    addPositionSlider(panel);
    addReformationControls(panel, resetActions);

    JButton reset = new JButton("Reset to defaults"); // NON-NLS
    reset.addActionListener(e -> resetActions.forEach(Runnable::run));
    panel.add(reset, "align right"); // NON-NLS

    JPopupMenu popup = new JPopupMenu();
    popup.add(panel);
    popup.show(invoker, x, y);
  }

  /** Slider that moves the position marker along the vessel (and the crosshair of the planes). */
  private void addPositionSlider(JPanel panel) {
    int count = curvedMprAxis.getSampledPoints().size();
    if (count < 2) {
      return;
    }
    double lengthMm = curvedMprAxis.getCenterlineLengthMm();
    JSlider slider =
        GuiUtils.createSlider(0, count - 1, Math.max(0, curvedMprAxis.getCursorColumn()), 4);
    JLabel label = new JLabel(positionText(slider.getValue(), count, lengthMm));
    slider.addChangeListener(
        e -> {
          label.setText(positionText(slider.getValue(), count, lengthMm));
          moveCursorToColumn(slider.getValue());
        });
    panel.add(label);
    panel.add(slider, "growx, w 260lp"); // NON-NLS
  }

  private static String positionText(int column, int count, double lengthMm) {
    double mm = count > 1 ? lengthMm * column / (count - 1.0) : 0;
    return "Position: %.2f cm".formatted(mm / 10.0); // NON-NLS
  }

  /**
   * Straightened / Stretched choice, the MIP slab thickness of the stretched view and the rotation
   * angle (around the vessel when straightened, around the vertical axis when stretched).
   */
  private void addReformationControls(JPanel panel, List<Runnable> resetActions) {
    boolean stretched = curvedMprAxis.getReformationType() == CurvedMprAxis.ReformationType.STRETCHED;
    JRadioButton straightenedButton = new JRadioButton("Straightened", !stretched); // NON-NLS
    JRadioButton stretchedButton = new JRadioButton("Stretched", stretched); // NON-NLS
    ButtonGroup group = new ButtonGroup();
    group.add(straightenedButton);
    group.add(stretchedButton);

    int slabInit = (int) Math.round(Math.max(1, Math.min(40, curvedMprAxis.getSlabMm())));
    JSlider slabSlider = GuiUtils.createSlider(1, 40, slabInit, 4);
    JLabel slabLabel = new JLabel("Thick slab (MIP): %d mm".formatted(slabInit)); // NON-NLS
    slabSlider.addChangeListener(
        e -> {
          slabLabel.setText("Thick slab (MIP): %d mm".formatted(slabSlider.getValue())); // NON-NLS
          if (!slabSlider.getValueIsAdjusting()) {
            curvedMprAxis.setSlabMm(slabSlider.getValue());
          }
        });

    int angleInit = (int) Math.round(curvedMprAxis.getAngleDeg()) % 360;
    JSlider angleSlider = GuiUtils.createSlider(0, 359, angleInit, 4);
    JLabel angleLabel = new JLabel("Angle: %d°".formatted(angleInit)); // NON-NLS
    angleSlider.addChangeListener(
        e -> {
          angleLabel.setText("Angle: %d°".formatted(angleSlider.getValue())); // NON-NLS
          if (!angleSlider.getValueIsAdjusting()) {
            curvedMprAxis.setAngleDeg(angleSlider.getValue());
          }
        });

    Runnable updateEnabled =
        () -> {
          boolean isStretched = stretchedButton.isSelected();
          slabSlider.setEnabled(isStretched);
          angleSlider.setEnabled(isStretched || curvedMprAxis.isVesselMode());
        };
    updateEnabled.run();
    straightenedButton.addActionListener(
        e -> {
          curvedMprAxis.setReformationType(CurvedMprAxis.ReformationType.STRAIGHTENED);
          updateEnabled.run();
        });
    stretchedButton.addActionListener(
        e -> {
          curvedMprAxis.setReformationType(CurvedMprAxis.ReformationType.STRETCHED);
          updateEnabled.run();
        });

    panel.add(straightenedButton, "split 2"); // NON-NLS
    panel.add(stretchedButton);
    panel.add(slabLabel);
    panel.add(slabSlider, "growx, w 260lp"); // NON-NLS
    panel.add(angleLabel);
    panel.add(angleSlider, "growx, w 260lp"); // NON-NLS
    resetActions.add(
        () -> {
          straightenedButton.doClick();
          slabSlider.setValue(10);
          angleSlider.setValue(0);
        });
  }

  /**
   * Append a header + slider that drives {@link CurvedMprAxis#setWidthMm}. The slider uses
   * voxel-aligned ticks so granularity matches the underlying sampling resolution.
   */
  private void addHeightSlider(
      JPanel panel, double pixelMm, String unitLabel, List<Runnable> resetActions) {
    double current = Math.max(1.0, curvedMprAxis.getWidthMm());
    double min = Math.min(5.0, current);
    double max = Math.max(200.0, current * 2);

    // 1 slider unit ≈ 1 voxel. Cap to avoid integer overflow on tiny pixelMm values.
    final int scale = Math.clamp(Math.round(1.0 / pixelMm), 1, 1000);
    int sliderMin = (int) Math.round(min * scale);
    int sliderMax = Math.max(sliderMin + 1, (int) Math.round(max * scale));
    int sliderInit = Math.clamp((int) Math.round(current * scale), sliderMin, sliderMax);

    JSlider slider = GuiUtils.createSlider(sliderMin, sliderMax, sliderInit, 4);
    JLabel label = new JLabel("Height: %.1f %s".formatted(current, unitLabel)); // NON-NLS
    slider.addChangeListener(
        e -> {
          double mm = slider.getValue() / (double) scale;
          label.setText("Height: %.1f %s".formatted(mm, unitLabel)); // NON-NLS
          if (!slider.getValueIsAdjusting()) {
            curvedMprAxis.setWidthMm(mm);
          }
        });
    panel.add(label);
    panel.add(slider, "growx, w 260lp"); // NON-NLS

    int defaultValue =
        Math.clamp((int) Math.round(CurvedMprAxis.DEFAULT_WIDTH_MM * scale), sliderMin, sliderMax);
    resetActions.add(() -> slider.setValue(defaultValue));
  }

  /**
   * Append a header + slider that drives {@link CurvedMprAxis#setStepMm}. The label includes the
   * resulting sample count (curve arc-length ÷ step) so the user understands the cost.
   *
   * <p>The panoramic's vertical resolution is one output row per {@code pixelMm} (the image height
   * ratio), so the range is anchored to it — from 0.5× (oversampled) to 10× (coarse) of a row —
   * rather than a fixed millimetre cap. This keeps the useful choices in range for both fine and
   * coarse volumes.
   */
  private void addStepSlider(
      JPanel panel, double pixelMm, String unitLabel, List<Runnable> resetActions) {
    double current = Math.max(pixelMm / 2.0, curvedMprAxis.getStepMm());
    double min = Math.min(pixelMm / 2.0, current);
    double max = Math.max(pixelMm * 10.0, current);

    // Granularity finer than a row; clamped to at least 10 slider units per mm (~0.1 mm).
    final int scale = Math.clamp(Math.round(4.0 / pixelMm), 10, 1000);
    int sliderMin = Math.max(1, (int) Math.round(min * scale));
    int sliderMax = Math.max(sliderMin + 1, (int) Math.round(max * scale));
    int sliderInit = Math.clamp((int) Math.round(current * scale), sliderMin, sliderMax);

    JSlider slider = new JSlider(sliderMin, sliderMax, sliderInit);
    slider.setMajorTickSpacing(Math.max(1, (sliderMax - sliderMin) / 4));
    slider.setPaintTicks(true);
    JLabel label = new JLabel();
    Runnable updateLabel =
        () -> {
          double mm = slider.getValue() / (double) scale;
          double arcLength = curvedMprAxis.getTotalArcLengthMm();
          int samples = mm > 0 ? (int) Math.ceil(arcLength / mm) : 0;
          label.setText("Step: %.2f %s (%d samples)".formatted(mm, unitLabel, samples)); // NON-NLS
        };
    updateLabel.run();
    slider.addChangeListener(
        e -> {
          updateLabel.run();
          if (!slider.getValueIsAdjusting()) {
            curvedMprAxis.setStepMm(slider.getValue() / (double) scale);
          }
        });
    panel.add(label);
    panel.add(slider, "growx, w 260lp"); // NON-NLS

    // Default sampling step is the volume's min pixel ratio (i.e. pixelMm).
    int defaultValue = Math.clamp((int) Math.round(pixelMm * scale), sliderMin, sliderMax);
    resetActions.add(() -> slider.setValue(defaultValue));
  }

  /**
   * The unit the height/step values are shown in. The panoramic image itself carries no {@code
   * PixelSpacing} (its X axis is arc-length, not Euclidean distance), so the slab parameters are
   * reported in the <em>source</em> volume's unit instead — {@code mm} when calibrated, {@code pix}
   * otherwise.
   */
  private String unitAbbreviation() {
    Unit unit = curvedMprAxis == null ? null : curvedMprAxis.getPixelSpacingUnit();
    return Objects.requireNonNullElse(unit, Unit.MILLIMETER).getAbbreviation();
  }

  /** Fall back to 1.0 if the volume reports a degenerate (0 or negative) pixel spacing. */
  private static double sanitizePixelMm(double pixelMm) {
    return pixelMm > 0 ? pixelMm : 1.0;
  }
}
