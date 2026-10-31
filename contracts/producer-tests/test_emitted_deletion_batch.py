"""Verify actual explicitly invoked maintenance output, not a public API."""
import json
from datetime import datetime
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

CONTRACTS = Path(__file__).resolve().parents[1]


class EmittedDeletionBatch(unittest.TestCase):
    def test_actual_deletion_batch_result_matches_contract(self):
        path = CONTRACTS.parent / 'build/generated-contract-fixtures/deletion-batch-result.json'
        self.assertTrue(path.is_file(), 'Run real PostgreSQL deletion batch tests first')
        schemas = {}
        registry = Registry()
        for source in (CONTRACTS / 'v1/schemas').glob('*.json'):
            schema = json.loads(source.read_text())
            schemas[source.name] = schema
            registry = registry.with_resource(schema['$id'], Resource.from_contents(schema))
        result = json.loads(path.read_text())
        Draft202012Validator(schemas['deletion-batch-result.json'], registry=registry,
                            format_checker=FormatChecker()).validate(result)
        cursor = result['nextCursor']
        if cursor is not None:
            self.assertEqual(cursor['app'], result['app'])
            self.assertEqual(cursor['cutoff'], result['cutoff'])
            self.assertLessEqual(datetime.fromisoformat(cursor['afterDeadline']),
                                 datetime.fromisoformat(result['cutoff']))
        # Strict schema rejects personal data, SQL, exception details, arbitrary
        # error maps and bearer capabilities anywhere in the result/cursor.


if __name__ == '__main__':
    unittest.main()
