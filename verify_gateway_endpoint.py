import requests
import json

url = "https://validator.nymtech.net/api/v1/unstable/nym-nodes/gateways/skimmed"
print(f"Fetching {url}...")
try:
    resp = requests.get(url, timeout=10)
    print(f"Status: {resp.status_code}")
    if resp.status_code == 200:
        data = resp.json()
        # It seems it returns a list of nodes directly or a specific structure
        nodes = data if isinstance(data, list) else data.get('nodes')
        if nodes:
            print(f"Total gateways: {len(nodes)}")
            print("First node sample:")
            print(json.dumps(nodes[0], indent=2))
            
            # Check for 'clients_port' or 'wss_port'
            has_client_port = any('clients_port' in n for n in nodes)
            print(f"Has 'clients_port': {has_client_port}")
            
        else:
            print("No nodes found in response")
            print(data.keys())
except Exception as e:
    print(f"Error: {e}")
