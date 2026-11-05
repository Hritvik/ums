"""Slice C association report: validate the report actually written to the outbox."""
import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

CONTRACTS = Path(__file__).resolve().parents[1]
ARTIFACTS = CONTRACTS.parent / 'build/generated-contract-fixtures'


class EmittedAssociationReport(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schemas = {}
        cls.registry = Registry()
        for source in (CONTRACTS / 'v1/schemas').glob('*.json'):
            schema = json.loads(source.read_text())
            cls.schemas[source.name] = schema
            cls.registry = cls.registry.with_resource(schema['$id'], Resource.from_contents(schema))

    def report(self, filename):
        path = ARTIFACTS / filename
        self.assertTrue(path.is_file(), 'Run AssociationReportTest first: ' + filename)
        value = json.loads(path.read_text())
        Draft202012Validator(self.schemas['association-report.json'], registry=self.registry,
                             format_checker=FormatChecker()).validate(value)
        return value

    def test_active_report_is_closed_and_minimal(self):
        report = self.report('association-report.json')
        self.assertEqual(report['eventType'], 'ums.profile.association-report.v1')
        self.assertEqual(report['aggregate']['type'], 'UMS_PROFILE_BINDING')
        self.assertEqual(report['payload']['appSubject'], report['aggregate']['id'])
        self.assertIn('generation', report['payload'])
        text = json.dumps(report)
        for forbidden in ('displayName', 'displayLocale', 'displayTimezone', 'hid_', 'Bearer', 'memberId'):
            self.assertNotIn(forbidden, text)

    def test_erased_report_is_a_tombstone(self):
        report = self.report('association-report-erased.json')
        self.assertEqual(report['payload']['lifecycleState'], 'ERASED')
        self.assertIn('retiredGeneration', report['payload'])
        self.assertNotIn('generation', report['payload'])
        self.assertNotIn('profileRevision', report['payload'])
        self.assertFalse(report['payload']['contentObligation'])


if __name__ == '__main__':
    unittest.main()
