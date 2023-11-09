import subprocess

# Define the list of scripts to run as subprocesses.
scripts = ['app_a.py', 'app_b.py', 'waise.py']


# List to keep track of the process objects.
processes = []

# Start each script as a subprocess.
for script in scripts:
    process = subprocess.Popen(['python3', script])
    processes.append(process)

# Optionally, wait for the processes to complete.
# If you want the script to proceed without waiting, you can comment these lines out.
for process in processes:
    process.wait()
