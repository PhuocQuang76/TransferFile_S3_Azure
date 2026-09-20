# EVEN HUB
# A — Create the Azure resources (you, in the Portal)
✅ Step 1 — Namespace (already done)
Namespace:  transfer-events
Group:      filetransfer-ns
Tier:       Standard
Region:     centralus

✅ Step 2 — Create the event hub
This is the piece you're missing. A namespace is just a container; the hub is what you actually send to.

Go to portal.azure.com
Search "Event Hubs" in the top bar → click it
Click your namespace transfer-events
In the left menu under Entities, click Event Hubs
Click + Event Hub at the top
Fill in:
Field       	Value
Name        	file-transfers
Partition count	2
Retention   	Delete / 1 hour–24 hours

✅ Step 3 — Create a Send-only access policy
You currently only have RootManageSharedAccessKey, which can Manage, Send and Listen.
A producer shouldn't be able to read or administer anything.

Still in the namespace, click Event Hubs → click file-transfers
In its left menu, setting/click Shared access policies
Click + Add
Fill in:

Field       	Value
Policy name	    send-only
Permissions	    ✅ Send only — leave Manage and Listen unticked
Click Create

✅ Step 4 - Finding the connection string
Your Event Hubs namespace → Settings → Shared access policies
You'll see RootManageSharedAccessKey by default.
Don't use it — it has Manage/Send/Listen. Create a Send-only one instead:
Click + Add
Name it send-only
Tick Send only → Create
Click it → copy Connection string–primary key
<PASTE-YOUR-CONNECTION-STRING-HERE>  # never commit the real value

✅Step 5 — Where you'll check it worked
Once your code is sending, come back here:
Namespace → file-transfers → Overview, or Monitoring → Metrics
Look for Incoming Messages. The count rising after a transfer run is 
your proof — and the screenshot the assignment wants as a deliverable.
Metrics lag a minute or two, so don't panic if it's flat immediately after your first send.

✅Step 6 — Delete it when you're done
Resource groups → filetransfer-ns → select the namespace → Delete
Delete the namespace, not just the hub — the namespace carries
the ~$22/month throughput-unit charge once your free period ends.

# PARTITION
A partition is an independent, ordered log inside the event hub.
The hub is split into them, and each event lands in exactly one.

Event Hub "file-transfers"
├── partition 0:  [evt][evt][evt][evt]  →  ordered
├── partition 1:  [evt][evt][evt]       →  ordered
└── partition 2:  [evt][evt][evt][evt]  →  ordered
                    ↑
            order is guaranteed WITHIN a partition, never across them

# What they give you
1. Parallelism. Each partition can be read by one consumer at a time. 
    Three partitions → up to three consumers reading simultaneously.
    Partitions are your consumer-side throughput ceiling.

2. Ordering — but only within a partition. With 3 partitions,
  an event written at 10:00 in partition 0 and one at 10:01 
  in partition 2 have no guaranteed relative order when read.

# How events get assigned
Method              	Behaviour
No key (default)    	round-robin — best balance, no ordering guarantee
Partition key   	    hashed → same key always hits the same partition, so those events stay ordered
Explicit partition id	you pick — rarely a good idea, creates hotspots


### APPLICATION  ###
# store eventhub String key:
* values-minikube.yaml (gitignored)
secret:
    data:
        AZURE_EVENTHUB_CONNECTION_STRING: "Endpoint=sb://..."
* CI — a GitHub secret
    AZURE_EVENTHUB_CONNECTION_STRING

* in heml chart values.yaml
  AZURE_EVENTHUB_CONNECTION_STRING: ""
* in deployment.yaml
  - name: AZURE_EVENTHUB_ENABLED
  value: {{ .Values.env.eventHubEnabled | default "false" | quote }}
  - name: AZURE_EVENTHUB_EVENT_HUB_NAME
  value: {{ .Values.env.eventHubName | default "file-transfers" | quote }}

* application.properties:
  azure.eventhub.connection-string=
  azure.eventhub.event-hub-name=file-transfers
  azure.eventhub.enabled=false

# Then run comman to merge values from values-minikube to values.yaml
    helm upgrade --install filetransfer ./filetransfer-chart -f filetransfer-chart/values-minikube.yaml








