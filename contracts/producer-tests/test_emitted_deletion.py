"""Slice D1 persisted outputs, with no erasure or live-route claim."""
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

CONTRACTS = Path(__file__).resolve().parents[1]
ARTIFACTS = CONTRACTS.parent / 'build/generated-contract-fixtures'


class EmittedDeletion(unittest.TestCase):
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
        self.assertTrue(path.is_file(), 'Run real PostgreSQL deletion tests first: ' + filename)
        value = json.loads(path.read_text())
        Draft202012Validator(self.schemas[schema_name], registry=self.registry,
                            format_checker=FormatChecker()).validate(value)
        return value

    def test_persisted_deletion_request_event(self):
        event = self.artifact('deletion-requested-event.json', 'profile-state-event.json')
        self.assertEqual(event['reason'], 'DELETION_REQUESTED')
        self.assertEqual(event['state']['lifecycle'], 'DELETION_PENDING')
        self.assertEqual(event['state']['readiness'], 'PROFILE_REQUIRED')
        self.assertIn('appOnly', event['state']['deletionCauses'])

    def test_persisted_cancellation_event(self):
        event = self.artifact('deletion-cancelled-event.json', 'profile-state-event.json')
        self.assertEqual(event['reason'], 'DELETION_CANCELLED')
        self.assertNotEqual(event['state']['lifecycle'], 'ERASED')
        self.assertNotIn('appOnly', event['state']['deletionCauses'])
        # Another cause may still restrict a cancelled app-only request.

    def test_deletion_acceptance(self):
        accepted = self.artifact('deletion-accepted.json', 'operation-accepted.json')
        self.assertEqual(accepted['status'], 'ACCEPTED')

    def test_persisted_erasure_event(self):
        event = self.artifact('deletion-erased-event.json', 'profile-state-event.json')
        self.assertEqual(event['reason'], 'ERASED')
        state = event['state']
        self.assertEqual(state['lifecycle'], 'ERASED')
        self.assertEqual(state['readiness'], 'PROFILE_REQUIRED')
        self.assertEqual(state['deletionCauses'], {})
        for key in ['profileReference', 'profileRevision', 'generation', 'fields', 'displayName', 'avatarReference']:
            self.assertNotIn(key, state)

    def test_locally_completed_deletion_status(self):
        status = self.artifact('deletion-completed-status.json', 'deletion-request-status.json')
        self.assertEqual(status['status'], 'LOCAL_COMPLETED')
        self.assertFalse(status['cancellationEligible'])

    def test_deletion_request_status(self):
        status = self.artifact('deletion-request-status.json', 'deletion-request-status.json')
        self.assertIsInstance(status['bindingRevision'], str)
        self.assertIsInstance(status['operationRevision'], str)


if __name__ == '__main__':
    unittest.main()
