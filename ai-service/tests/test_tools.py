from app.tools import project


def test_project_key_is_normalized():
    assert project({"project": "kin"}) == "KIN"


def test_project_key_rejects_jql_fragments():
    try:
        project({"project": 'KIN" OR project=BEK'})
    except ValueError:
        pass
    else:
        raise AssertionError("unsafe project key was accepted")
