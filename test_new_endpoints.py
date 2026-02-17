import requests

base_url = "https://validator.nymtech.net/api/v1"
endpoints = [
    "/nym-nodes/described",
    "/unstable/nym-nodes/skimmed/entry-gateways/active",
    "/unstable/nym-nodes/skimmed/entry-gateways/all",
    "/unstable/nym-nodes/gateways/skimmed"
]

for ep in endpoints:
    url = base_url + ep
    print(f"Testing {url}...")
    try:
        resp = requests.get(url, timeout=10)
        print(f"Status: {resp.status_code}")
        if resp.status_code == 200:
            data = resp.json()
            if "nodes" in data:
                print(f"Count (nodes): {len(data['nodes'])}")
            elif "data" in data:
                print(f"Count (data): {len(data['data'])}")
                # Inspect first item
                if len(data['data']) > 0:
                    print(f"First item keys: {data['data'][0].keys()}")
    except Exception as e:
        print(f"Error: {e}")
