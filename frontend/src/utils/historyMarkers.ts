import type { RetrainingEvent } from '../types/models';

export interface ContinuityMarker {
  type: 'between_events' | 'above_newest';
  fromVersion: string;
  toVersion: string;
  message: string;
  earlierCreatedAt?: string;
  laterCreatedAt?: string;
}

export type TimelineElement =
  | { type: 'event'; data: RetrainingEvent }
  | { type: 'marker'; marker: ContinuityMarker };

/**
 * Pure function to format the continuity marker message.
 */
export function formatMarkerMessage(fromVersion: string, toVersion: string): string {
  return `Active model changed outside the retraining workflow: ${fromVersion} -> ${toVersion} (for example a manual rollback or a database change).`;
}

/**
 * Pure function to detect continuity markers across retraining events.
 * 1. Sorts events oldest to newest.
 * 2. For each consecutive pair (earlier, later) where earlier.activeAfter and later.activeBefore
 *    are both non-null and differ, creates a marker. Pairs with a null field are skipped.
 * 3. If the newest event's activeAfter is non-null and differs from currentActiveVersion,
 *    creates a marker above the newest event.
 */
export function findContinuityMarkers(
  events: RetrainingEvent[],
  currentActiveVersion: string | null = null
): ContinuityMarker[] {
  if (!events || events.length === 0) {
    return [];
  }

  // Sort events oldest to newest (ascending)
  const sorted = [...events].sort(
    (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
  );

  const markers: ContinuityMarker[] = [];

  // Check consecutive pairs
  for (let i = 0; i < sorted.length - 1; i++) {
    const earlier = sorted[i];
    const later = sorted[i + 1];

    if (
      earlier.activeAfter !== null &&
      earlier.activeAfter !== undefined &&
      later.activeBefore !== null &&
      later.activeBefore !== undefined &&
      earlier.activeAfter !== later.activeBefore
    ) {
      markers.push({
        type: 'between_events',
        fromVersion: earlier.activeAfter,
        toVersion: later.activeBefore,
        message: formatMarkerMessage(earlier.activeAfter, later.activeBefore),
        earlierCreatedAt: earlier.createdAt,
        laterCreatedAt: later.createdAt,
      });
    }
  }

  // Check newest event vs current active version returned by /models
  const newest = sorted[sorted.length - 1];
  if (
    newest &&
    newest.activeAfter !== null &&
    newest.activeAfter !== undefined &&
    currentActiveVersion !== null &&
    currentActiveVersion !== undefined &&
    newest.activeAfter !== currentActiveVersion
  ) {
    markers.push({
      type: 'above_newest',
      fromVersion: newest.activeAfter,
      toVersion: currentActiveVersion,
      message: formatMarkerMessage(newest.activeAfter, currentActiveVersion),
      earlierCreatedAt: newest.createdAt,
    });
  }

  return markers;
}

/**
 * Builds an interleaved timeline list (newest first) for UI rendering.
 * Includes events and continuity markers.
 */
export function buildTimelineElements(
  events: RetrainingEvent[],
  currentActiveVersion: string | null = null
): TimelineElement[] {
  if (!events || events.length === 0) {
    return [];
  }

  // Sort events oldest to newest (ascending)
  const sorted = [...events].sort(
    (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
  );

  const ascendingElements: TimelineElement[] = [];

  for (let i = 0; i < sorted.length; i++) {
    const current = sorted[i];
    ascendingElements.push({ type: 'event', data: current });

    // Check gap between current (earlier) and next (later)
    if (i < sorted.length - 1) {
      const next = sorted[i + 1];
      if (
        current.activeAfter !== null &&
        current.activeAfter !== undefined &&
        next.activeBefore !== null &&
        next.activeBefore !== undefined &&
        current.activeAfter !== next.activeBefore
      ) {
        ascendingElements.push({
          type: 'marker',
          marker: {
            type: 'between_events',
            fromVersion: current.activeAfter,
            toVersion: next.activeBefore,
            message: formatMarkerMessage(current.activeAfter, next.activeBefore),
            earlierCreatedAt: current.createdAt,
            laterCreatedAt: next.createdAt,
          },
        });
      }
    }
  }

  // Check newest vs currentActiveVersion
  const newest = sorted[sorted.length - 1];
  if (
    newest &&
    newest.activeAfter !== null &&
    newest.activeAfter !== undefined &&
    currentActiveVersion !== null &&
    currentActiveVersion !== undefined &&
    newest.activeAfter !== currentActiveVersion
  ) {
    ascendingElements.push({
      type: 'marker',
      marker: {
        type: 'above_newest',
        fromVersion: newest.activeAfter,
        toVersion: currentActiveVersion,
        message: formatMarkerMessage(newest.activeAfter, currentActiveVersion),
        earlierCreatedAt: newest.createdAt,
      },
    });
  }

  // Return descending (newest first)
  return ascendingElements.reverse();
}
