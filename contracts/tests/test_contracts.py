import json
import pathlib
import unittest
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

ROOT = pathlib.Path(__file__).resolve().parents[1] / 'v1'

class Contracts(unittest.TestCase):
    def test_fixture_manifest(self):
        manifest = json.loads((ROOT / 'fixtures/manifest.json').read_text())
        self.assertGreaterEqual(len(manifest), 20)
        registry = Registry()
        schemas = {}
        for path in (ROOT / 'schemas').glob('*.json'):
            schema = json.loads(path.read_text())
            Draft202012Validator.check_schema(schema)
            schemas[path.name] = schema
            registry = registry.with_resource(schema['$id'], Resource.from_contents(schema))
        for case in manifest:
            with self.subTest(case=case['file']):
                value = json.loads((ROOT / 'fixtures' / case['file']).read_text())
                validator = Draft202012Validator(schemas[case['schema']], registry=registry, format_checker=FormatChecker())
                self.assertEqual(validator.is_valid(value), case['valid'])


class ContractBundle(unittest.TestCase):
    def test_openapi_is_design_only_and_refs_exist(self):
        spec = json.loads((ROOT / 'openapi.json').read_text())
        self.assertEqual(spec['openapi'], '3.1.0')
        self.assertNotIn('servers', spec)
        def visit(node):
            if isinstance(node, dict):
                if '$ref' in node:
                    target = node['$ref']
                    self.assertTrue(target.startswith('./schemas/'))
                    self.assertTrue((ROOT / target).is_file(), target)
                for value in node.values():
                    visit(value)
            elif isinstance(node, list):
                for value in node:
                    visit(value)
        visit(spec)

    def test_task_surface_has_batch_and_private_errors(self):
        spec = json.loads((ROOT / 'openapi.json').read_text())
        path = '/internal/v1/apps/{appId}/profiles/display-reads'
        self.assertIn(path, spec['paths'])
        operation = spec['paths'][path]['post']
        self.assertIn('requestBody', operation)
        for path_item in spec['paths'].values():
            for method, operation in path_item.items():
                if method not in ['get', 'post', 'put', 'patch']:
                    continue
                for code in ['400', '401', '403', '404', '409', '422', '429', '500', '503']:
                    self.assertIn('content', operation['responses'][code])

    def test_deletion_status_requires_distinct_binding_revision(self):
        schema = json.loads((ROOT / 'schemas/deletion-request-status.json').read_text())
        self.assertIn('bindingRevision', schema['required'])
        self.assertEqual(schema['properties']['bindingRevision']['$ref'], 'common.json#/$defs/version')

    def test_version_boundaries_are_lossless(self):
        common = json.loads((ROOT / 'schemas/common.json').read_text())
        validator = Draft202012Validator(common['$defs']['version'])
        for value in ['0', '9', '99', '9007199254740993', '9223372036854775807']:
            self.assertTrue(validator.is_valid(value), value)
        for value in ['9223372036854775808', '9999999999999999999', 9007199254740993, '1.0', '+1', '00']:
            self.assertFalse(validator.is_valid(value), value)

if __name__ == '__main__':
    unittest.main()
