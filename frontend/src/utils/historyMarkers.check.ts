/**
 * Standalone runnable check for history continuity marker pure functions.
 * Run with:
 *   node src/utils/historyMarkers.check.ts
 */

import {
  findContinuityMarkers,
  buildTimelineElements,
} from './historyMarkers.ts';
import type { RetrainingEvent } from '../types/models.ts';

function assertStrictEqual<T>(actual: T, expected: T, message?: string): void {
  if (actual !== expected) {
    throw new Error(
      message ? `${message}: expected ${expected}, got ${actual}` : `Expected ${expected}, got ${actual}`
    );
  }
}

function assertOk(condition: unknown, message?: string): void {
  if (!condition) {
    throw new Error(message || 'Condition was not truthy');
  }
}

function createDummyEvent(
  createdAt: string,
  activeBefore: string | null,
  activeAfter: string | null,
  entityId: string = 'model'
): RetrainingEvent {
  return {
    action: 'MODEL_RETRAIN_ATTEMPTED',
    entityId,
    createdAt,
    eligible: true,
    activationGatePassed: true,
    reason: null,
    activeBefore,
    activeAfter,
    totalVerdicts: 100,
    fraudCount: 50,
    legitimateCount: 50,
    candidatePrAuc: 0.85,
  };
}

// -----------------------------------------------------------------------------
// Test 1: The three real events in this database
// Expect exactly one marker, between the 2026-10-23 11:25 event and the 2026-10-25 event
// -----------------------------------------------------------------------------
function testThreeRealEvents() {
  console.log('Running Test 1: Three real events from PayGuard database...');

  const realEvents: RetrainingEvent[] = [
    // Provided in reverse/random order to verify sorting logic
    createDummyEvent(
      '2026-10-25T09:35:36.068507Z',
      'xgboost-v1',
      'xgboost-v1',
      'xgboost-v1'
    ),
    createDummyEvent(
      '2026-10-23T11:25:30.119371Z',
      'xgboost-v1',
      'xgboost-v2',
      'xgboost-v2'
    ),
    createDummyEvent(
      '2026-10-23T11:02:58.323171Z',
      'xgboost-v1',
      'xgboost-v1',
      'xgboost-v1'
    ),
  ];

  const currentActiveVersion = 'xgboost-v1';

  const markers = findContinuityMarkers(realEvents, currentActiveVersion);
  assertStrictEqual(markers.length, 1, 'Expected exactly one marker for real events');

  const marker = markers[0];
  assertStrictEqual(marker.type, 'between_events');
  assertStrictEqual(marker.fromVersion, 'xgboost-v2');
  assertStrictEqual(marker.toVersion, 'xgboost-v1');
  assertStrictEqual(
    marker.earlierCreatedAt,
    '2026-10-23T11:25:30.119371Z',
    'Marker must be associated with the 2026-10-23 11:25 event as earlier'
  );
  assertStrictEqual(
    marker.laterCreatedAt,
    '2026-10-25T09:35:36.068507Z',
    'Marker must be associated with the 2026-10-25 event as later'
  );
  assertStrictEqual(
    marker.message,
    'Active model changed outside the retraining workflow: xgboost-v2 -> xgboost-v1 (for example a manual rollback or a database change).'
  );

  // Check timeline elements (newest first)
  const timeline = buildTimelineElements(realEvents, currentActiveVersion);
  assertStrictEqual(timeline.length, 4, 'Timeline should contain 3 events + 1 marker');
  assertStrictEqual(timeline[0].type, 'event');
  assertStrictEqual((timeline[0] as any).data.createdAt, '2026-10-25T09:35:36.068507Z');
  assertStrictEqual(timeline[1].type, 'marker', 'Marker must be placed between 2026-10-25 and 2026-10-23 11:25');
  assertStrictEqual((timeline[1] as any).marker.fromVersion, 'xgboost-v2');
  assertStrictEqual((timeline[1] as any).marker.toVersion, 'xgboost-v1');
  assertStrictEqual(timeline[2].type, 'event');
  assertStrictEqual((timeline[2] as any).data.createdAt, '2026-10-23T11:25:30.119371Z');
  assertStrictEqual(timeline[3].type, 'event');
  assertStrictEqual((timeline[3] as any).data.createdAt, '2026-10-23T11:02:58.323171Z');

  console.log('  ✓ Test 1 passed (1 marker detected between 2026-10-23 11:25 and 2026-10-25).');
}

// -----------------------------------------------------------------------------
// Test 2: Continuous sequence (no marker)
// -----------------------------------------------------------------------------
function testContinuousSequence() {
  console.log('Running Test 2: Continuous sequence (no marker)...');

  const continuousEvents: RetrainingEvent[] = [
    createDummyEvent('2026-10-20T10:00:00Z', 'v1', 'v2'),
    createDummyEvent('2026-10-21T10:00:00Z', 'v2', 'v3'),
    createDummyEvent('2026-10-22T10:00:00Z', 'v3', 'v4'),
  ];

  const currentActiveVersion = 'v4';

  const markers = findContinuityMarkers(continuousEvents, currentActiveVersion);
  assertStrictEqual(markers.length, 0, 'Continuous sequence must yield 0 markers');

  const timeline = buildTimelineElements(continuousEvents, currentActiveVersion);
  assertStrictEqual(timeline.length, 3, 'Timeline must contain only the 3 events');
  assertOk(timeline.every((t) => t.type === 'event'));

  console.log('  ✓ Test 2 passed (0 markers in continuous sequence).');
}

// -----------------------------------------------------------------------------
// Test 3: Pair with null fields (no marker)
// -----------------------------------------------------------------------------
function testPairWithNullFields() {
  console.log('Running Test 3: Pair with null fields (skipped, no marker)...');

  const nullEvents: RetrainingEvent[] = [
    createDummyEvent('2026-10-20T10:00:00Z', 'v1', null),
    createDummyEvent('2026-10-21T10:00:00Z', null, 'v2'),
    createDummyEvent('2026-10-22T10:00:00Z', 'v2', 'v2'),
  ];

  const currentActiveVersion = 'v2';

  const markers = findContinuityMarkers(nullEvents, currentActiveVersion);
  assertStrictEqual(markers.length, 0, 'Pairs with null fields must be skipped');

  const timeline = buildTimelineElements(nullEvents, currentActiveVersion);
  assertStrictEqual(timeline.length, 3);
  assertOk(timeline.every((t) => t.type === 'event'));

  console.log('  ✓ Test 3 passed (0 markers for null field pairs).');
}

// -----------------------------------------------------------------------------
// Test 4: Newest event whose activeAfter differs from active version (marker above newest)
// -----------------------------------------------------------------------------
function testNewestDiffersFromActiveVersion() {
  console.log('Running Test 4: Newest event activeAfter differs from active version...');

  const events: RetrainingEvent[] = [
    createDummyEvent('2026-10-20T10:00:00Z', 'v1', 'v2'),
    createDummyEvent('2026-10-21T10:00:00Z', 'v2', 'v2'),
  ];

  // System currently active version is v3, but newest event left activeAfter as v2
  const currentActiveVersion = 'v3';

  const markers = findContinuityMarkers(events, currentActiveVersion);
  assertStrictEqual(markers.length, 1, 'Expected 1 marker above newest event');

  const marker = markers[0];
  assertStrictEqual(marker.type, 'above_newest');
  assertStrictEqual(marker.fromVersion, 'v2');
  assertStrictEqual(marker.toVersion, 'v3');
  assertStrictEqual(
    marker.message,
    'Active model changed outside the retraining workflow: v2 -> v3 (for example a manual rollback or a database change).'
  );

  const timeline = buildTimelineElements(events, currentActiveVersion);
  assertStrictEqual(timeline.length, 3, 'Timeline should have 1 marker + 2 events');
  assertStrictEqual(timeline[0].type, 'marker', 'Marker must be above the newest event in descending order');
  assertStrictEqual((timeline[0] as any).marker.fromVersion, 'v2');
  assertStrictEqual((timeline[0] as any).marker.toVersion, 'v3');
  assertStrictEqual(timeline[1].type, 'event');
  assertStrictEqual((timeline[1] as any).data.createdAt, '2026-10-21T10:00:00Z');
  assertStrictEqual(timeline[2].type, 'event');
  assertStrictEqual((timeline[2] as any).data.createdAt, '2026-10-20T10:00:00Z');

  console.log('  ✓ Test 4 passed (marker rendered above newest event).');
}

// -----------------------------------------------------------------------------
// Main execution
// -----------------------------------------------------------------------------
function runAllChecks() {
  console.log('===============================================================');
  console.log('HISTORY CONTINUITY MARKER TEST SUITE');
  console.log('===============================================================');
  testThreeRealEvents();
  testContinuousSequence();
  testPairWithNullFields();
  testNewestDiffersFromActiveVersion();
  console.log('===============================================================');
  console.log('ALL 4 TEST SCENARIOS PASSED SUCCESSFULLY');
  console.log('===============================================================');
}

runAllChecks();
