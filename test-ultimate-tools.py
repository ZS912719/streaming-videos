#!/usr/bin/env python3
"""Independent parser, normalization and exact evaluator regression checks."""
import importlib.util
import tempfile
import subprocess
import unittest
from pathlib import Path

spec=importlib.util.spec_from_file_location('ultimate','ultimate-experiments.py')
study=importlib.util.module_from_spec(spec)
spec.loader.exec_module(study)


class ExactToolsTest(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory()
        self.root=Path(self.directory.name)
        self.input=self.root/'original.in'
        self.input.write_text('2 1 3 2 10\n5 7\n1000 2\n0 10\n1 20\n0 0 12000\n0 0 13000\n1 0 4000000000\n')

    def tearDown(self):
        self.directory.cleanup()

    def test_actual_support_and_normalization(self):
        # Aggregated counts are preserved without scaling, clipping or integer narrowing.
        self.input.write_text(self.input.read_text().replace('4000000000','10000'))
        p=study.parse(self.input)
        self.assertEqual(p['demands'][0,0],25000)
        derived=self.root/'normalized.in'
        self.assertTrue(study.exact_normalize(p,derived))
        self.assertEqual(study.parse(derived)['demands'],p['demands'])
        output=self.root/'solution.txt';output.write_text('2\n0 0\n1 1\n')
        self.assertEqual(study.evaluate(p,output),study.evaluate(study.parse(derived),output))
        self.assertEqual(study.evaluate(p,output)[0],25000*990+10000*980)

    def test_reject_duplicates_capacity_and_bad_ids(self):
        self.input.write_text(self.input.read_text().replace('4000000000','10000'))
        p=study.parse(self.input)
        for text in ['1\n0 0 0\n','1\n0 0 1\n','1\n-1 0\n','1\n0 2\n','2\n0 0\n0 1\n','0\nextra\n']:
            output=self.root/'bad.txt';output.write_text(text)
            with self.assertRaises(ValueError):study.evaluate(p,output)

    def test_big_integer_scoring(self):
        # Pure evaluator is deliberately independent of fixed-width Java/C++ numeric assumptions.
        p=dict(v=1,c=1,x=1,sizes=[1],endpoints=[(4000,{0:1})],demands={(0,0):10**30},total=10**30)
        output=self.root/'wide.txt';output.write_text('1\n0 0\n')
        self.assertEqual(study.evaluate(p,output),(3999*10**30,3999000))

    def test_long_counts_in_input(self):
        p=study.parse(self.input)
        self.assertEqual(p['total'],4000025000)

    def test_line_ending_archive_identity(self):
        other=self.root/'crlf.in'
        self.input.write_bytes(self.input.read_bytes().replace(b'\r\n',b'\n'))
        other.write_bytes(self.input.read_bytes().replace(b'\n',b'\r\n'))
        self.assertNotEqual(study.digest(self.input),study.digest(other))
        self.assertEqual(study.canonical_input_digest(self.input),study.canonical_input_digest(other))
        other.write_bytes(other.read_bytes().replace(b'12000',b'12001'))
        self.assertNotEqual(study.canonical_input_digest(self.input),study.canonical_input_digest(other))

    @unittest.skipUnless(Path('tmp/judge-exact.exe').exists(),'Build the optional strict C++ judge first')
    def test_strict_cpp_wide_and_malformed_outputs(self):
        p=study.parse(self.input)
        output=self.root/'long-solution.txt';output.write_text('2\n0 0\n1 1\n')
        self.assertEqual(study.old.judge(self.input,output,Path('tmp/judge-exact.exe')),study.evaluate(p,output)[1])
        for text in ['1\n0 0 0\n','1\n0 999999999999999999999999\n','1\n0 -1\n','1\n0 0 1\n']:
            output.write_text(text)
            process=subprocess.run([str(Path('tmp/judge-exact.exe').resolve()),str(self.input),str(output)],capture_output=True)
            self.assertNotEqual(process.returncode,0)

    def test_wrong_sign_trailing_and_header(self):
        for text in ['-1 1 1 1 1\n','1 1 1 1 1\n1\n1000 0\n0 0 -1\n',
                     '1 1 1 1 1\n1\n1000 0\n0 0 1\ntrailing\n']:
            self.input.write_text(text)
            with self.assertRaises(ValueError):study.parse(self.input)


if __name__=='__main__':
    unittest.main()
