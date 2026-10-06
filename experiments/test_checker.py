import unittest
import json
from checker import check

class CheckerTest(unittest.TestCase):
    def setUp(self):
        self.expected=[dict(eventId="e1",runId="r",sellerId="s",orderId=1,sequence=1,operation="CREATE",quantity=2,price=3)]
        self.row=dict(event_id="e1",run_id="r",seller_id="s",order_id=1,seq=1,operation="CREATE",quantity=2,price=3)
        self.row['payload']=json.dumps(self.expected[0])
        self.remote=dict(effects=[self.row],orders=[self.row],attempts=[])
    def test_correct_effect(self):
        self.assertTrue(check(self.expected,self.remote,[self.row],[self.row])["passed"])
    def test_missing_effect(self):
        self.remote["effects"]=[]
        self.assertFalse(check(self.expected,self.remote,[self.row],[self.row])["passed"])
    def test_duplicate_effect(self):
        self.remote["effects"]=[self.row,self.row]
        self.assertFalse(check(self.expected,self.remote,[self.row],[self.row])["passed"])
    def test_wrong_final_payload(self):
        self.remote["orders"]=[dict(self.row,quantity=999)]
        self.assertFalse(check(self.expected,self.remote,[self.row],[self.row])["passed"])
    def test_internal_effect_attributed_to_another_order(self):
        self.assertFalse(check(self.expected,self.remote,[dict(self.row,order_id=2)],[self.row])["passed"])
    def test_external_effect_attributed_to_another_run(self):
        self.remote["effects"]=[dict(self.row,payload=json.dumps(dict(self.expected[0],runId="another")))]
        self.assertFalse(check(self.expected,self.remote,[self.row],[self.row])["passed"])
    def test_wrong_order(self):
        self.expected.append(dict(self.expected[0],eventId="e2",sequence=2,operation="CHANGE"))
        second=dict(self.row,event_id="e2",seq=2,operation="CHANGE")
        second['payload']=json.dumps(self.expected[1])
        self.remote["effects"]=[second,self.row]
        self.remote["orders"]=[second]
        self.assertFalse(check(self.expected,self.remote,[self.row,second],[second])["passed"])

if __name__=="__main__": unittest.main()
