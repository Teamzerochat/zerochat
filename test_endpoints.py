import requests

base_url = "https://validator.nymtech.net/api/v1"
endpoints = [
    "/gateways",
    "/gateways/",
    "/gateways/bonded",
    "/gateways/active",
    "/status/gateways",
    "/status/gateway/bonded",
    "/mixnet/gateways",
    "/network/gateways",
    "/directory/gateways",
    "/topology",
    "/network/topology",
    "/status/mixmining/topology",
    "/mixnodes",
    "/mixnodes/bonded"
]

for ep in endpoints:
    url = base_url + ep
    try:
        resp = requests.get(url, timeout=5)
        print(f"{ep}: {resp.status_code}")
        if resp.status_code == 200:
             print(f"  Length: {len(resp.text)}")
             # Check if it looks like a list of gateways
             try:
                 data = resp.json()
                 if isinstance(data, list):
                     print(f"  List size: {len(data)}")
                 elif isinstance(data, dict):
                     if "nodes" in data:
                         print(f"  Nodes count: {len(data['nodes'])}")
                     if "gateways" in data:
                         print(f"  Gateways count: {len(data['gateways'])}")
             except:
                 pass
    except Exception as e:
        print(f"{ep}: Error {e}")
