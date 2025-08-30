import sys
import ast
import pandas as pd

data = {"ID": [], "query": [], "intent": [], "value": [], "clickables": []}

with open("eval/test.txt", "r", encoding="utf-8") as f:
    txt = f.read()

with open("eval/apps.txt", "r", encoding="utf-8") as f:
    apps = f.read().split("\n")

for idx, line in enumerate(txt.split("\n"), start=1):
    query, output, candidates = line.split("\t")

    try:
        query = query.strip()
        intent, value = output.lstrip("(").rstrip(")").split(",", maxsplit=1)
        intent = str(intent).strip().upper()
        value = [item.strip() for item in str(value).strip().split(",")]

        candidates = ast.literal_eval(candidates)
        if candidates:
            candidates = candidates[1:]
        candidates = [str(cand).strip().replace(" ", "_") for cand in candidates]

        if intent == "SWIPE":
            assert value[0] in [
                "UP",
                "DOWN",
                "LEFT",
                "RIGHT",
            ], f"Unexpected value({value[0]}) when SWIPE"
        elif intent == "PRESS":
            assert value[0] in candidates, (
                f"No value({value[0]}) in candidates when PRESS"
            )
        elif intent == "OPEN":
            for item in value:
                assert item in apps, f"No value({value}) in apps when OPEN"

        data["ID"].append(idx)
        data["query"].append(query)
        data["intent"].append(intent)
        data["value"].append(value)
        data["clickables"].append(candidates)

    except Exception as e:
        print("Error:", e)
        print(query, output, candidates, sep=" | ")
        print("=" * 20)
        # sys.exit(1)

df = pd.DataFrame(data)
df.to_csv("eval/test.csv", index=False, encoding="utf-8")
