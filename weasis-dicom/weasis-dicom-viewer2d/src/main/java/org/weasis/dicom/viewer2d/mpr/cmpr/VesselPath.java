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

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;

/**
 * Ordered 3D points clicked by the user along a vessel, in voxel-ratio-scaled coordinates. The
 * points can be placed from any MPR plane; they are displayed on all three planes and are the input
 * of the vessel curved MPR (used as is, or as seeds of the automatic centerline).
 */
public final class VesselPath {
  public static final String PROP_POINTS = "points"; // NON-NLS

  private final List<Vector3d> points = new ArrayList<>();
  private final PropertyChangeSupport changes = new PropertyChangeSupport(this);

  public void addPropertyChangeListener(PropertyChangeListener listener) {
    changes.addPropertyChangeListener(listener);
  }

  public void removePropertyChangeListener(PropertyChangeListener listener) {
    changes.removePropertyChangeListener(listener);
  }

  /** A copy of the points, in the order they were added. */
  public synchronized List<Vector3d> getPoints() {
    List<Vector3d> copy = new ArrayList<>(points.size());
    for (Vector3d p : points) {
      copy.add(new Vector3d(p));
    }
    return copy;
  }

  public synchronized int size() {
    return points.size();
  }

  public void addPoint(Vector3d point) {
    if (point == null) {
      return;
    }
    synchronized (this) {
      points.add(new Vector3d(point));
    }
    changes.firePropertyChange(PROP_POINTS, null, null);
  }

  public void removeLast() {
    synchronized (this) {
      if (points.isEmpty()) {
        return;
      }
      points.removeLast();
    }
    changes.firePropertyChange(PROP_POINTS, null, null);
  }

  public void clear() {
    synchronized (this) {
      if (points.isEmpty()) {
        return;
      }
      points.clear();
    }
    changes.firePropertyChange(PROP_POINTS, null, null);
  }
}
