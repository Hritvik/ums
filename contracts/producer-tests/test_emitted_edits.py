"""Validate Slice B runtime output against the published UMS contract.

Artifacts must come from real PostgreSQL tests; these checks do not fabricate
producer output and never resolve schema references over the network.
"""
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource


CONTRACTS = Path(__file__).resolve().parents[1]
ARTIFACTS = CONTRACTS.parent / "build/generated-contract-fixtures"


class EmittedProfileEdits(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schemas = {}
        cls.registry = Registry()
        for source in (CONTRACTS / "v1/schemas").glob("*.json"):
            schema = json.loads(source.read_text())
            cls.schemas[source.name] = schema
            cls.registry = cls.registry.with_resource(
                schema["$id"], Resource.from_contents(schema)
            )

    def artifact(self, filename, schema_name):
        path = ARTIFACTS / filename
        self.assertTrue(path.is_file(), "Run real PostgreSQL Gradle tests before producerContractTest: " + filename)
        value = json.loads(path.read_text())
        Draft202012Validator(
            self.schemas[schema_name],
            registry=self.registry,
            format_checker=FormatChecker(),
        ).validate(value)
        return value

    def test_persisted_edit_event_matches_published_contract(self):
        event = self.artifact("edited-event.json", "profile-state-event.json")
        self.assertEqual(event["reason"], "FIELDS_CHANGED")
        self.assertEqual(event["state"]["bindingRevision"], "2")
        self.assertEqual(event["state"]["profileRevision"], "2")
        self.assertEqual(event["state"]["readiness"], "PROFILE_READY")
        forbidden = {
            "displayName", "avatarReference", "displayLocale", "displayTimezone",
            "email", "phone", "humanIdentityId", "token", "metadata", "fields",
        }
        def check_keys(node):
            if isinstance(node, dict):
                self.assertFalse(forbidden.intersection(node), "Personal/extension field in state event")
                for value in node.values():
                    check_keys(value)
            elif isinstance(node, list):
                for value in node:
                    check_keys(value)
        check_keys(event)

    def test_edited_status_matches_published_contract(self):
        status = self.artifact("status-after-edit.json", "profile-status.json")
        self.assertEqual(status["state"]["readiness"], "PROFILE_READY")
        self.assertEqual(status["state"]["profileRevision"], "2")
        self.assertEqual(status["unmetFields"], [])

    def test_edited_display_matches_published_contract(self):
        display = self.artifact("display-after-edit.json", "profile-display.json")
        self.assertEqual(display["displayNameSource"], "EXPLICIT")
        self.assertEqual(display["profileRevision"], "2")

    def test_display_batch_matches_published_contract(self):
        self.artifact("display-batch.json", "display-read-response.json")


if __name__ == "__main__":
    unittest.main()
