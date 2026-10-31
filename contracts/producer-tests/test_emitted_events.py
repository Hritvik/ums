"""Checks persisted producer output, not another hand-authored schema fixture."""
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource


CONTRACTS = Path(__file__).resolve().parents[1]
REPOSITORY = CONTRACTS.parent


class EmittedProfileEvents(unittest.TestCase):
    def test_persisted_provisioning_event_matches_published_contract(self):
        path = REPOSITORY / "build/generated-contract-fixtures/provisioned.json"
        self.assertTrue(path.is_file(), "Run Gradle test before producerContractTest")
        schemas = {}
        registry = Registry()
        for source in (CONTRACTS / "v1/schemas").glob("*.json"):
            schema = json.loads(source.read_text())
            schemas[source.name] = schema
            registry = registry.with_resource(schema["$id"], Resource.from_contents(schema))
        validator = Draft202012Validator(
            schemas["profile-state-event.json"],
            registry=registry,
            format_checker=FormatChecker(),
        )
        event = json.loads(path.read_text())
        validator.validate(event)
        self.assertEqual(event["reason"], "PROVISIONED")
        self.assertEqual(event["state"]["bindingRevision"], "1")
        self.assertEqual(event["state"]["lifecycle"], "ACTIVE")
        self.assertEqual(event["state"]["readiness"], "PROFILE_READY")


if __name__ == "__main__":
    unittest.main()
