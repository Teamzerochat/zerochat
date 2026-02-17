import requests
import json

try:
    response = requests.get("https://validator.nymtech.net/api/v1/nym-nodes/bonded")
    response.raise_for_status()
    data = response.json()
    
    nodes = data.get("nodes", [])
    print(f"Total nodes: {len(nodes)}")
    
    gateways = []
    for item in nodes:
        # Check if 'node' or 'bond_information' -> 'node' exists
        node_info = item.get("bond_information", {}).get("node", {})
        # Inspect keys to find gateway indicators
        # Valid indicators: 'clients_port', or maybe 'roles' if it exists?
        # Let's print the keys of the first node to see what's available
        if len(gateways) == 0:
             print(f"First node keys: {node_info.keys()}")
        
        # Heuristic: Gateways usually have a clients_port (9000). 
        # But nym-nodes might structure it differently.
        # Let's look for 'entry' in roles if it exists.
        
        # If 'clients_port' is in node_info, it's likely a gateway.
        if "clients_port" in node_info:
            gateways.append(item)
            
    print(f"Nodes with clients_port: {len(gateways)}")

except Exception as e:
    print(f"Error: {e}")
