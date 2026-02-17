import requests
import json

url = "https://validator.nymtech.net/api/v1/unstable/nym-nodes/skimmed/entry-gateways/active"
print(f"Fetching {url}...")
try:
    resp = requests.get(url, timeout=10)
    print(f"Status: {resp.status_code}")
    if resp.status_code == 200:
        data = resp.json()
        nodes = data if isinstance(data, list) else data.get('nodes')
        if nodes is None and isinstance(data, dict):
             # Maybe it's just a list of objects?
             pass
        
        if isinstance(data, list):
            nodes = data

        if nodes:
            print(f"Total active gateways: {len(nodes)}")
            print("First node sample:")
            print(json.dumps(nodes[0], indent=2))
        else:
            print("No nodes found or unrecognized format")
            if isinstance(data, dict):
                print(data.keys())
except Exception as e:
    print(f"Error: {e}")
