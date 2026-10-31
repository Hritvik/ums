"""Cross-check real UMS artifacts used by the test-only consumer journey."""
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

CONTRACTS = Path(__file__).resolve().parents[1]
ARTIFACTS = CONTRACTS.parent / 'build/generated-contract-fixtures'


class ConsumerJourneyArtifacts(unittest.TestCase):
    def test_journey_event_status_and_display_share_one_profile_revision(self):
        schemas = {}
        registry = Registry()
        for source in (CONTRACTS / 'v1/schemas').glob('*.json'):
            schema = json.loads(source.read_text())
            schemas[source.name] = schema
            registry = registry.with_resource(schema['$id'], Resource.from_contents(schema))
        values = {}
        for name, schema in [('event', 'profile-state-event.json'),
                             ('status', 'profile-status.json'),
                             ('display', 'profile-display.json')]:
            path = ARTIFACTS / ('journey-' + name + '.json')
            self.assertTrue(path.is_file(), 'Run PostgreSQL consumer journey before producerContractTest: ' + path.name)
            values[name] = json.loads(path.read_text())
            Draft202012Validator(schemas[schema], registry=registry,
                                 format_checker=FormatChecker()).validate(values[name])
        event, status, display = (values[name] for name in ['event', 'status', 'display'])
        self.assertEqual(event['reason'], 'FIELDS_CHANGED')
        self.assertEqual(event['state'], status['state'])
        self.assertEqual(event['state']['readiness'], 'PROFILE_READY')
        self.assertEqual(event['state']['profileReference'], display['profileReference'])
        self.assertEqual(event['state']['profileRevision'], display['profileRevision'])
        self.assertEqual(display['displayNameSource'], 'EXPLICIT')
        self.assertEqual(status['unmetFields'], [])
