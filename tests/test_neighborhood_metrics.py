"""Boundary and accounting checks for accepted raw gain per elapsed second."""

import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location(
    'neighborhood_benchmark', Path(__file__).resolve().parents[1] / 'benchmark-neighborhoods.py')
BENCHMARK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BENCHMARK)


class SecondBinTest(unittest.TestCase):
    def test_boundaries_partial_second_and_post_stop_padding(self):
        record = dict(dataset='tiny.in', budget_ms='4000', variant='two', repetition=1,
                      search_ms='2500', accepted_moves='3', saved_latency_gain='31',
                      termination='no_improving_move')
        trace = [{}] + [dict(elapsed_ms=t, saved_latency_gain=g)
                        for t, g in [('999.999', '7'), ('1000.000', '11'), ('2499.999', '13')]]
        rows = BENCHMARK.second_bins(record, trace)
        self.assertEqual([7, 11, 13, 0], [r['raw_gain_request_ms'] for r in rows])
        self.assertEqual([7, 18, 31, 31], [r['cumulative_raw_gain_request_ms'] for r in rows])
        self.assertEqual([1000, 1000, 500, 0], [r['active_ms'] for r in rows])
        self.assertEqual([False, False, False, True], [r['after_search_stop'] for r in rows])

    def test_no_moves_is_not_an_invented_improvement(self):
        record = dict(dataset='tiny.in', budget_ms='3000', variant='one', repetition=1,
                      search_ms='500', accepted_moves='0', saved_latency_gain='0',
                      termination='no_improving_move')
        rows = BENCHMARK.second_bins(record, [{}])
        self.assertEqual([0, 0, 0], [r['raw_gain_request_ms'] for r in rows])
        self.assertEqual([500, 0, 0], [r['active_ms'] for r in rows])


if __name__ == '__main__':
    unittest.main()
