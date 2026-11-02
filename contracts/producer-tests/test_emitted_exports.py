"""Slice E2 export capability and artifact, validated against the published schemas."""
import json
import re
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

CONTRACTS = Path(__file__).resolve().parents[1]
ARTIFACTS = CONTRACTS.parent / 'build/generated-contract-fixtures'


class EmittedExports(unittest.TestCase):
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
        self.assertTrue(path.is_file(), 'Run real PostgreSQL export tests first: ' + filename)
        value = json.loads(path.read_text())
        Draft202012Validator(self.schemas[schema_name], registry=self.registry,
                            format_checker=FormatChecker()).validate(value)
        return value

    def test_download_capability(self):
        capability = self.artifact('export-download.json', 'export-download.json')
        self.assertRegex(capability['token'], re.compile(r'^[A-Za-z0-9_-]{43}$'))
        self.assertIn('exportReference', capability)

    def test_artifact_contains_only_ums_owned_fields(self):
        artifact = self.artifact('export-artifact.json', 'export-artifact.json')
        self.assertIn(artifact['lifecycle'], ('ACTIVE', 'CLOSED', 'DELETION_PENDING', 'ERASED'))
        for key in ('appSubject', 'subjectReference', 'identityId', 'email', 'phone', 'token', 'credential'):
            self.assertNotIn(key, artifact)


if __name__ == '__main__':
    unittest.main()
