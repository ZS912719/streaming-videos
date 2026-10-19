#!/usr/bin/env python3
"""Small standard-library checks for V2 experiment admission and record protection."""
import argparse
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('v2_experiments','ultimate-v2-experiments.py')
v2=importlib.util.module_from_spec(spec);sys.modules[spec.name]=v2;spec.loader.exec_module(v2)


class ExperimentTests(unittest.TestCase):
    def test_frozen_source_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as name:
            area=Path(name)
            (area/'solver-configuration.json').write_text(json.dumps(dict(source_sha256='wrong',canonical_source_sha256='wrong')))
            with patch.object(v2,'AREA',area):
                with self.assertRaises(ValueError):v2.frozen()

    def test_canonical_checkout_source_identity(self):
        with tempfile.TemporaryDirectory() as name:
            area=Path(name)
            config=dict(source_sha256='different-line-endings',canonical_source_sha256=v2.u.canonical_source_hash())
            (area/'solver-configuration.json').write_text(json.dumps(config))
            with patch.object(v2,'AREA',area):self.assertEqual(v2.frozen(),config)

    def test_export_cannot_erase_a_better_fresh_record(self):
        with tempfile.TemporaryDirectory() as name:
            area=Path(name);(area/'final-rows').mkdir()
            old=area/'old.txt';old.write_text('old')
            better=area/'fresh.txt';better.write_text('better')
            common=dict(order=1,instance='sample.in',input_sha256='input',wall_seconds=1,algorithm_seconds=1)
            inc=dict(common,score=9,raw_objective=90,solution=str(old),solution_sha256=v2.u.digest(old))
            fresh=dict(common,score=10,raw_objective=100,solution=str(better),solution_sha256=v2.u.digest(better))
            (area/'final-rows'/'incumbent-01.json').write_text(json.dumps(inc))
            (area/'final-rows'/'fresh-01.json').write_text(json.dumps(fresh))
            with patch.object(v2,'AREA',area):v2.export(argparse.Namespace())
            rows=v2.u.read_csv(area/'all-33-best-known-results.csv')
            self.assertEqual(rows[0]['score'],'10')
            self.assertEqual(Path(rows[0]['archived_solution']).read_text(),'better')
            self.assertEqual(old.read_text(),'old')
            self.assertEqual((area/'scores-only.txt').read_text(),'10\n')

    def test_wrong_input_cannot_import_a_pilot_record(self):
        with tempfile.TemporaryDirectory() as name:
            area=Path(name);(area/'final-rows').mkdir();(area/'runs'/'pilot').mkdir(parents=True)
            source=area/'old.txt';source.write_text('valid-baseline')
            row=dict(order=1,instance='sample.in',input_sha256='input',score=10,raw_objective=100,
                     solution=str(source),solution_sha256=v2.u.digest(source),wall_seconds=1,algorithm_seconds=1)
            (area/'final-rows'/'incumbent-01.json').write_text(json.dumps(row))
            (area/'runs'/'pilot'/'validated.json').write_text(json.dumps(dict(input_sha256='other-input',raw_objective=100000)))
            with patch.object(v2,'AREA',area):v2.export(argparse.Namespace())
            self.assertEqual(v2.u.read_csv(area/'all-33-best-known-results.csv')[0]['score'],'10')


if __name__=='__main__':unittest.main()
