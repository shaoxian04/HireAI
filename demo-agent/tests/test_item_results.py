from app import item_results


def test_each_item_is_answered_by_its_tag():
    results = item_results([
        {"itemNo": 1, "input": "INV-001"},
        {"itemNo": 2, "input": "INV-002 #empty"},
        {"itemNo": 3, "input": "INV-003 #fail"},
        {"itemNo": 4, "input": "INV-004 #skip"},
    ])

    assert [r["itemNo"] for r in results] == [1, 2, 3]
    assert results[0]["agentStatus"] == "COMPLETED"
    assert "INV-001" in results[0]["resultPayloadJson"]
    assert results[1]["resultPayloadJson"] == ""
    assert results[2] == {"itemNo": 3, "agentStatus": "FAILED"}
