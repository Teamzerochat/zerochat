import requests
import json

url = "https://validator.nymtech.net/api/v1/unstable/nym-nodes/gateways/skimmed"
try:
    resp = requests.get(url, timeout=10)
    if resp.status_code == 200:
        nodes = resp.json()
        print(f"Total nodes: {len(nodes)}")
        
        has_wss = 0
        has_ws = 0
        active_role = 0
        high_perf = 0
        
        for n in nodes:
            entry = n.get('entry', {})
            if entry and entry.get('wss_port'):
                has_wss += 1
            if entry and entry.get('ws_port'):
                has_ws += 1
            
            if n.get('role') == 'Active': # Case sensitive?
                 active_role += 1
            
            # check performance field
            perf = n.get('performance')
            if perf and float(perf) > 0:
                high_perf += 1

        print(f"With WSS port: {has_wss}")
        print(f"With WS port: {has_ws}")
        print(f"Role Active: {active_role}")
        print(f"Performance > 0: {high_perf}")
        
        # Print one node as sample
        print(json.dumps(nodes[0], indent=2))

except Exception as e:
    print(f"Error: {e}")
