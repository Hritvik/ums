"""Slice D closure outputs. Closure starts retention; it does not erase anything."""
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

CONTRACTS = Path(__file__).resolve().parents[1]
ARTIFACTS = CONTRACTS.parent / 'build/generated-contract-fixtures'


class EmittedClosure(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schemas = {}
        cls.registry = Registry()
        for source in (CONTRACTS / 'v1/schemas').glob('*.json'):
            schema = json.loads(source.read_text())
            cls.schemas[source.name] = schema
            cls.registry = cls.registry.with_resource(schema['$id'], Resource.from_contents(schema))

    def artifact(self, filename, schema_name):
        path = ARTIFACTS / filename
        self.assertTrue(path.is_file(), 'Run real PostgreSQL closure tests first: ' + filename)
        value = json.loads(path.read_text())
        Draft202012Validator(self.schemas[schema_name], registry=self.registry,
                            format_checker=FormatChecker()).validate(value)
        return value

    def test_persisted_closure_event(self):
        event = self.artifact('closure-event.json', 'profile-state-event.json')
        self.assertEqual(event['reason'], 'CLOSED')
        state = event['state']
        self.assertEqual(state['lifecycle'], 'CLOSED')
        self.assertEqual(state['readiness'], 'PROFILE_REQUIRED')
        self.assertEqual(state['deletionCauses'], {})
        # Closure is not erasure: the retained generation is still identified.
        self.assertIn('profileReference', state)
        self.assertIn('generation', state)
        for key in ['displayName', 'avatarReference', 'displayLocale', 'displayTimezone']:
            self.assertNotIn(key, state)

    def test_closed_status_publishes_retention(self):
        status = self.artifact('closure-status.json', 'profile-status.json')
        self.assertEqual(status['state']['lifecycle'], 'CLOSED')
        self.assertEqual(status['nextActions'], ['REQUEST_REACTIVATION'])
        self.assertIn('retentionDeadline', status)
        self.assertEqual(status['unmetFields'], [])

    def test_persisted_reactivation_event(self):
        event = self.artifact('reactivated-event.json', 'profile-state-event.json')
        self.assertEqual(event['reason'], 'REACTIVATED')
        state = event['state']
        self.assertEqual(state['lifecycle'], 'ACTIVE')
        self.assertEqual(state['readiness'], 'PROFILE_READY')
        self.assertEqual(state['deletionCauses'], {})
        for key in ['displayName', 'avatarReference', 'displayLocale', 'displayTimezone']:
            self.assertNotIn(key, state)

    def test_reactivated_status_reports_no_retention(self):
        status = self.artifact('reactivated-status.json', 'profile-status.json')
        self.assertEqual(status['state']['lifecycle'], 'ACTIVE')
        self.assertEqual(status['nextActions'], [])
        self.assertNotIn('retentionDeadline', status)

    def test_persisted_expiry_event(self):
        event = self.artifact('expired-event.json', 'profile-state-event.json')
        self.assertEqual(event['reason'], 'ERASED')
        state = event['state']
        self.assertEqual(state['lifecycle'], 'ERASED')
        self.assertEqual(state['readiness'], 'PROFILE_REQUIRED')
        self.assertEqual(state['deletionCauses'], {})
        for key in ['profileReference', 'generation', 'profileRevision',
                    'displayName', 'avatarReference', 'displayLocale', 'displayTimezone']:
            self.assertNotIn(key, state)


if __name__ == '__main__':
    unittest.main()
