import matplotlib.pyplot as plt
import numpy as np

# --- 1. SET UP SAMPLE DATA ---
# (Using derived data from source benchmarks for context)
time = np.linspace(0, 150, 1000)
availability_bridge = np.where(time < 7.1, 0, 1)  # (Entry Latency ~7.1s)
availability_hardened = np.where(time < 81.2, 0, 1) # (Entry Latency ~81.2s)

# --- 2. CREATE THE PLOT ---
fig, ax = plt.subplots(figsize=(8, 5))

# Plot lines
ax.step(time, availability_bridge, label='Service Available (Mixnet Bridge)', color='orange', linewidth=2)
ax.step(time, availability_hardened, label='Tunnel Hardened (I2P Fortress)', color='blue', linestyle='--', linewidth=2)

# Set axes aesthetics (for clean appearance)
ax.set_xlabel('Time (s)', fontsize=12)
ax.set_ylabel('Connection State (0=Idle, 1=Active)', fontsize=12)
ax.set_title('ZeroChat Phased Latency', fontsize=14, fontweight='bold')
ax.set_yticks([0, 1])
ax.set_yticklabels(['Idle', 'Active'])
ax.grid(True, linestyle=':', alpha=0.6)

# --- 3. APPLY A LEGEND POSITIONING FIX ---

# --- FIX A: Position Legend Outside Plot Area (Highly Recommended) ---
# This anchors the 'upper left' of the legend box at (x=1.05, y=1) on the figure,
# placing it completely outside and to the right of the plotting axes.
# ax.legend(bbox_to_anchor=(1.05, 1), loc='upper left', frameon=True)

# --- FIX B: Position with Precise Padding Inside empty corner ---
# Controls padding (0.02 figure fraction) from the 'upper left' (loc=2) for better alignment
# in the top-left empty space.
ax.legend(bbox_to_anchor=(0.56, 0), loc='lower left', frameon=True, shadow=True)

# --- FIX C: Use 'best' location with increased padding ---
# Asks matplotlib to find the optimal empty spot, with extra padding from axes.
# ax.legend(loc='best', borderaxespad=1.0, frameon=True)

# --- 4. FINALIZE ---
plt.tight_layout() # Ensures legend isn't cut off if placed outside
plt.savefig("latency_timeline_fixed.png", dpi=300)
plt.show()

print("Visualization generated with legend position fix applied!")