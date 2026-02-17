import requests
import json

try:
    print("Fetching nym-nodes...")
    response = requests.get("https://validator.nymtech.net/api/v1/nym-nodes/bonded")
    if response.status_code == 200:
        data = response.json()
        print(f"Keys: {data.keys()}")
        if "data" in data:
            nodes_list = data["data"]
            print(f"Number of nodes in 'data': {len(nodes_list)}")
            
            gateways = []
            for item in nodes_list:
                # Inspect item structure
                # It might be item['bond_information']['node']
                node_info = item.get("bond_information", {}).get("node", {})
                
                # Check for client port or roles
                # Actually, look for 'entry' capability if it exists?
                # Or just print the keys of the first node to be sure.
                if len(gateways) == 0:
                     print(f"First node item keys: {item.keys()}")
                     print(f"First node info: {node_info}")
                
                # Try to identify gateways:
                # 1. Check for 'clients_port' (unlikely based on previous look)
                # 2. Check if 'custom_http_port' is 8080/8000/9000?
                # 3. Maybe there's a specific field I missed.
                
                if "clients_port" in node_info:
                    gateways.append(item)
                    
            print(f"Nodes with clients_port: {len(gateways)}")
    else:
        print(f"Failed to fetch nym-nodes: {response.status_code}")

except Exception as e:
    print(f"Error: {e}")
