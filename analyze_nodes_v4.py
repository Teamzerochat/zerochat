import requests
import json

try:
    print("Fetching nym-nodes...")
    response = requests.get("https://validator.nymtech.net/api/v1/nym-nodes/bonded")
    if response.status_code == 200:
        data = response.json()
        if "data" in data:
            nodes_list = data["data"]
            print(f"Number of nodes: {len(nodes_list)}")
            
            all_node_keys = set()
            all_bond_keys = set()
            all_status_keys = set()
            
            gateways_found = 0
            
            for item in nodes_list:
                bond = item.get("bond_information", {})
                node = bond.get("node", {})
                status = item.get("status", {})
                
                all_bond_keys.update(bond.keys())
                all_node_keys.update(node.keys())
                # all_status_keys.update(status.keys()) # status might be a string or dict
                
                # Check for any field that might indicate gateway
                # Look for 'mix_port' (mixnodes have this) vs 'clients_port' (gateways have this)
                # But previous run showed only 'custom_http_port'.
                
                # Maybe 'declared_roles' exists in bond?
                
            print(f"All Node Keys: {all_node_keys}")
            print(f"All Bond Keys: {all_bond_keys}")
            # print(f"All Status Keys: {all_status_keys}")

            if len(nodes_list) > 0:
                print(f"First Status: {nodes_list[0].get('status')}")
                
    else:
        print(f"Failed: {response.status_code}")

except Exception as e:
    print(f"Error: {e}")
