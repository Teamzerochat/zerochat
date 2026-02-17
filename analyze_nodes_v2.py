import requests
import json

try:
    response = requests.get("https://validator.nymtech.net/api/v1/nym-nodes/bonded")
    if response.status_code == 200:
        data = response.json()
        print(f"Data type: {type(data)}")
        if isinstance(data, dict):
            print(f"Keys: {data.keys()}")
            if "nodes" in data:
                print(f"Number of nodes: {len(data['nodes'])}")
                if len(data['nodes']) > 0:
                    print(f"First node: {data['nodes'][0]}")
        elif isinstance(data, list):
             print(f"List length: {len(data)}")
             if len(data) > 0:
                 print(f"First item: {data[0]}")
    else:
        print(f"Failed: {response.status_code}")

    # Also check mixnodes just in case
    print("Checking mixnodes...")
    resp_mix = requests.get("https://validator.nymtech.net/api/v1/mixnodes")
    print(f"Mixnodes status: {resp_mix.status_code}")

except Exception as e:
    print(f"Error: {e}")
