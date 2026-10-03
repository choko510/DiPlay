# Backlog

- Run the K706/UIS8581 NCM A/B sequence with debug profile logs: STATUS_POLLING first, pre-ready OUT timeout next if needed, then SYNC_BULK_IN and forced 5→6/1 only if still inconclusive. Keep the post-startup UsbRequest queue/wait failure as a separate finding and select a production fix only from repeatable device evidence.
