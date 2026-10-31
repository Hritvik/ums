"""Verify a read-only provider bundle without extracting or fetching schemas."""
import hashlib
import json
from pathlib import Path
import unittest
import zipfile

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource


DEPENDENCY = Path(__file__).resolve().parents[1] / 'dependencies/iam'


class IamDependency(unittest.TestCase):
    def bundle(self):
        lock = json.loads((DEPENDENCY / 'lock.json').read_text())
        archive = DEPENDENCY / lock['archive']
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        self.assertEqual(digest, lock['sha256'])
        checksum = (DEPENDENCY / lock['checksumFile']).read_text().split()
        self.assertEqual(checksum, [digest, lock['archive']])
        return lock, zipfile.ZipFile(archive)

    def test_provider_checksum_and_locked_source_inventory(self):
        lock, archive = self.bundle()
        with archive:
            members = {n: hashlib.sha256(archive.read(n)).hexdigest()
                       for n in archive.namelist() if not n.endswith('/')}
        self.assertEqual(members, lock['entries'])
        self.assertEqual(len(members), 80)
        self.assertEqual(lock['version'], '1.0.0')

    def test_provider_schemas_and_valid_fixtures_offline(self):
        lock, archive = self.bundle()
        with archive:
            schemas = {}
            registry = Registry()
            for name in archive.namelist():
                if name.endswith('.schema.json'):
                    schema = json.loads(archive.read(name))
                    Draft202012Validator.check_schema(schema)
                    schemas[schema['$id']] = schema
                    registry = registry.with_resource(schema['$id'], Resource.from_contents(schema))
            self.assertEqual(len(schemas), 12)
            checked = 0
            for name in archive.namelist():
                if '/fixtures/valid/' not in name or not name.endswith('.json'):
                    continue
                fixture = json.loads(archive.read(name))
                with self.subTest(fixture=name):
                    Draft202012Validator(schemas[fixture['schema']], registry=registry,
                                        format_checker=FormatChecker()).validate(fixture['instance'])
                checked += 1
            self.assertEqual(checked, 10)
            # Some provider negatives are cross-field/security semantics, deliberately
            # beyond schema validation. Lock the exact schema-only disposition;
            # this does not claim implementation of provider semantic requirements.
            accepted = []
            checked = 0
            for name in archive.namelist():
                if '/fixtures/invalid/' not in name or not name.endswith('.json'):
                    continue
                fixture = json.loads(archive.read(name))
                validator = Draft202012Validator(schemas[fixture['schema']], registry=registry,
                                                format_checker=FormatChecker())
                if validator.is_valid(fixture['instance']):
                    accepted.append(name.split('/fixtures/invalid/')[1])
                checked += 1
            self.assertEqual(checked, 53)
            self.assertEqual(sorted(accepted), lock['schemaOnlySemanticNegatives'])


if __name__ == '__main__':
    unittest.main()
