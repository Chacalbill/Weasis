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
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import net.miginfocom.swing.MigLayout;
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
import org.weasis.dicom.viewer2d.mpr.MprContainer;

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

    DicomImageElement img = axis.getImageElement();
    if (img != null) {
      DicomSeries series = new DicomSeries("curved-mpr-" + System.currentTimeMillis());
      series.addMedia(img);
      setSeries(series, img);
    }
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

  /** Yellow vertical line at the position marker (the green dot of the three MPR planes). */
  private void drawCursor(Graphics2D g2d, CurvedMprAxis axis) {
    int column = axis.getCursorColumn();
    if (column < 0 || column >= axis.getSampledPoints().size()) {
      return;
    }
    double pixelMm = sanitizePixelMm(axis.getVolume().getMinPixelRatio());
    double rows = Math.max(1, Math.round(axis.getWidthMm() / pixelMm));
    java.awt.Point top = getMouseCoordinatesFromImage(column + 0.5, 0);
    java.awt.Point bottom = getMouseCoordinatesFromImage(column + 0.5, rows);
    java.awt.Stroke oldStroke = g2d.getStroke();
    Color oldColor = g2d.getColor();
    g2d.setStroke(new java.awt.BasicStroke(1.5f));
    g2d.setColor(Color.YELLOW);
    g2d.drawLine(top.x, top.y, bottom.x, bottom.y);
    g2d.setStroke(oldStroke);
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
                "fillx, ins 6lp, wrap 1", "[grow, fill]", "[]2lp[]8lp[]2lp[]8lp[]2lp[]8lp[]2lp[]8lp[]")); // NON-NLS

    List<Runnable> resetActions = new ArrayList<>(2);
    addHeightSlider(panel, pixelMm, unitLabel, resetActions);
    addStepSlider(panel, pixelMm, unitLabel, resetActions);
    addPositionSlider(panel);
    if (curvedMprAxis.isVesselMode()) {
      addAngleSlider(panel, resetActions);
    }

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

  /** Slider that rotates the panoramic plane around the vessel (0-360 degrees). */
  private void addAngleSlider(JPanel panel, List<Runnable> resetActions) {
    int current = (int) Math.round(curvedMprAxis.getAngleDeg()) % 360;
    JSlider slider = GuiUtils.createSlider(0, 359, current, 4);
    JLabel label = new JLabel("Angle: %d°".formatted(current)); // NON-NLS
    slider.addChangeListener(
        e -> {
          label.setText("Angle: %d°".formatted(slider.getValue())); // NON-NLS
          if (!slider.getValueIsAdjusting()) {
            curvedMprAxis.setAngleDeg(slider.getValue());
          }
        });
    panel.add(label);
    panel.add(slider, "growx, w 260lp"); // NON-NLS
    resetActions.add(() -> slider.setValue(0));
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
