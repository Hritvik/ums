"""Check actual JVM adapter input against the pinned provider artifact."""
import hashlib
import json
from pathlib import Path
import unittest
import zipfile

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource


CONTRACTS = Path(__file__).resolve().parents[1]


class IamAdapterInput(unittest.TestCase):
    def test_actual_adapter_input_matches_pinned_iam_contract(self):
        fixture = CONTRACTS.parent / 'build/generated-contract-fixtures/iam-onboarding-input.json'
        self.assertTrue(fixture.is_file(), 'Run PostgreSQL adapter Gradle tests before producerContractTest')
        lock = json.loads((CONTRACTS / 'dependencies/iam/lock.json').read_text())
        bundle = CONTRACTS / 'dependencies/iam' / lock['archive']
        self.assertEqual(hashlib.sha256(bundle.read_bytes()).hexdigest(), lock['sha256'])
        schemas = {}
        registry = Registry()
        with zipfile.ZipFile(bundle) as archive:
            for name in archive.namelist():
                if name.endswith('.schema.json'):
                    schema = json.loads(archive.read(name))
                    schemas[schema['$id']] = schema
                    registry = registry.with_resource(schema['$id'], Resource.from_contents(schema))
        schema = schemas['https://contracts.distracted.dev/iam/events/iam.app-subject.onboarding-ready.v1.schema.json']
        event = json.loads(fixture.read_text())
        Draft202012Validator(schema, registry=registry, format_checker=FormatChecker()).validate(event)
        self.assertEqual(event['destination']['appRegistrationId'], event['payload']['appRegistrationId'])
        self.assertEqual(event['aggregate']['id'], event['payload']['activationId'])
        self.assertEqual(event['aggregate']['version'], event['payload']['onboardingVersion'])


if __name__ == '__main__':
    unittest.main()
