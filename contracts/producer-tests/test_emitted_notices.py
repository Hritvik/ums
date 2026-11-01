"""Slice E notice intents. UMS publishes an intent; it never claims a notice was sent."""
import json
import re
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

CONTRACTS = Path(__file__).resolve().parents[1]
ARTIFACTS = CONTRACTS.parent / 'build/generated-contract-fixtures'


class EmittedNotices(unittest.TestCase):
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
        self.assertTrue(path.is_file(), 'Run real PostgreSQL notice tests first: ' + filename)
        value = json.loads(path.read_text())
        Draft202012Validator(self.schemas[schema_name], registry=self.registry,
                            format_checker=FormatChecker()).validate(value)
        return value

    def test_persisted_notice_intent(self):
        intent = self.artifact('notice-intent-producer.json', 'notice-intent.json')
        self.assertIn(intent['noticeClass'], ('CLOSURE_EXPIRY_14_DAY', 'CLOSURE_EXPIRY_7_DAY'))
        self.assertEqual(intent['state'], 'QUEUED')
        self.assertRegex(intent['dedupeIdentity'], re.compile(r'^[a-f0-9]{64}$'))
        self.assertEqual(intent['producer'], 'ums')
        # The intent carries no channel, contact detail, wording or personal value.
        for key in ('email', 'phone', 'displayName', 'channel', 'message', 'body', 'deliveryAddress'):
            self.assertNotIn(key, intent)

    def test_dedupe_identity_is_class_and_closure_bound(self):
        intent = self.artifact('notice-intent-producer.json', 'notice-intent.json')
        self.assertEqual(len(intent['dedupeIdentity']), 64)
        self.assertNotIn(str(intent['scope']['subjectReference']), intent['dedupeIdentity'])


if __name__ == '__main__':
    unittest.main()
