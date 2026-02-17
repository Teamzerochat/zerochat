import requests
import json

url = "https://validator.nymtech.net/api/v1/unstable/nym-nodes/gateways/skimmed"
try:
    resp = requests.get(url, timeout=10)
    if resp.status_code == 200:
        data = resp.json()
        print(f"Type: {type(data)}")
        if isinstance(data, dict):
            print(f"Keys: {data.keys()}")
            # It's probably 'nodes'
            if 'nodes' in data:
                nodes = data['nodes']
            elif 'data' in data:
                nodes = data['data']
            else:
                nodes = []
        elif isinstance(data, list):
            nodes = data
        else:
            nodes = []

        print(f"Total nodes: {len(nodes)}")
        
        has_wss = 0
        active_role = 0
        high_perf = 0
        
        for n in nodes:
            entry = n.get('entry', {})
            # Check safely
            if entry and isinstance(entry, dict) and entry.get('wss_port'):
                has_wss += 1
            
            if n.get('role') == 'Active':
                 active_role += 1
            
            perf = n.get('performance')
            if perf and float(perf) > 0:
                high_perf += 1

        print(f"With WSS port: {has_wss}")
        print(f"Role Active: {active_role}")
        print(f"Performance > 0: {high_perf}")
        
except Exception as e:
    print(f"Error: {e}")
