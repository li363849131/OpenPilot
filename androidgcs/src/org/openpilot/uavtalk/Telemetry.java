package org.openpilot.uavtalk;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Observable;
import java.util.Observer;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;

import org.openpilot.uavtalk.UAVObject.Acked;

import android.util.Log;

public class Telemetry {
	
	private final String TAG = "Telemetry";
	public static int LOGLEVEL = 0;
	public static boolean WARN = LOGLEVEL > 1;
	public static boolean DEBUG = LOGLEVEL > 0;

    public class TelemetryStats {
        public int txBytes;
        public int rxBytes;
        public int txObjectBytes;
        public int rxObjectBytes;
        public int rxObjects;
        public int txObjects;
        public int txErrors;
        public int rxErrors;
        public int txRetries;
    } ;
    
    class ObjectTimeInfo {
        UAVObject obj;
        int updatePeriodMs; /** Update period in ms or 0 if no periodic updates are needed */
        int timeToNextUpdateMs; /** Time delay to the next update */
    };

    class ObjectQueueInfo {
        UAVObject obj;
        int event;
        boolean allInstances;
    };

    class ObjectTransactionInfo {
        UAVObject obj;
        boolean allInstances;
        boolean objRequest;
        int retriesRemaining;
        Acked acked;
    } ;
    
    /**
     * Events generated by objects.  Not enum because used in mask.
     */
    private static final int EV_UNPACKED = 0x01;       /** Object data updated by unpacking */
    private static final int EV_UPDATED = 0x02;        /** Object data updated by changing the data structure */
    private static final int EV_UPDATED_MANUAL = 0x04; /** Object update event manually generated */
    private static final int EV_UPDATE_REQ = 0x08;     /** Request to update object data */
    
    /**
     * Constructor
     */
    public Telemetry(UAVTalk utalk, UAVObjectManager objMngr)
    {
        this.utalk = utalk;
        this.objMngr = objMngr;

        // Process all objects in the list
        List< List<UAVObject> > objs = objMngr.getObjects();
        ListIterator<List<UAVObject>> li = objs.listIterator();
        while(li.hasNext()) 
        	registerObject(li.next().get(0)); // we only need to register one instance per object type

        // Listen to new object creations
        objMngr.addNewInstanceObserver(new Observer() {
			public void update(Observable observable, Object data) {
        		newInstance((UAVObject) data);        		
			}
        });
        objMngr.addNewObjectObserver(new Observer() {
			public void update(Observable observable, Object data) {
        		newObject((UAVObject) data);        		
        	}        	
        });

        // Listen to transaction completions
        utalk.addObserver(new Observer() {
			public void update(Observable observable, Object data) {
				transactionCompleted((UAVObject) data);				
			}        	
        });
        
        // Get GCS stats object
        gcsStatsObj = objMngr.getObject("GCSTelemetryStats");

        // Setup transaction timer
        transPending = false;
        // Setup and start the periodic timer
        timeToNextUpdateMs = 0;
        updateTimerSetPeriod(1000);
        // Setup and start the stats timer
        txErrors = 0;
        txRetries = 0;
    }
    
    synchronized void transTimerSetPeriod(int periodMs) {
    	if(transTimerTask != null)
    		transTimerTask.cancel();
    	
    	if(transTimer != null) 
    		transTimer.purge();

   		transTimer = new Timer();
    	
        transTimerTask = new TimerTask() {
			@Override
			public void run() {
				transactionTimeout();
			}        	
        };
        transTimer.schedule(transTimerTask, periodMs, periodMs);
    }
    
    synchronized void updateTimerSetPeriod(int periodMs) {
        updateTimer = new Timer();
        updateTimerTask = new TimerTask() {
			@Override
			public void run() {
				processPeriodicUpdates();
			}        	
        };
        updateTimer.schedule(updateTimerTask, periodMs, periodMs);

    }

    /**
     * Register a new object for periodic updates (if enabled)
     */
    private synchronized void registerObject(UAVObject obj)
    {
        // Setup object for periodic updates
        addObject(obj);

        // Setup object for telemetry updates
        updateObject(obj);
    }

    /**
     * Add an object in the list used for periodic updates
     */
    private synchronized void addObject(UAVObject obj)
    {
        // Check if object type is already in the list
    	ListIterator<ObjectTimeInfo> li = objList.listIterator();
    	while(li.hasNext()) {
    		ObjectTimeInfo n = li.next();
    		if( n.obj.getObjID() == obj.getObjID() )
            {
                // Object type (not instance!) is already in the list, do nothing
                return;
            }
        }

        // If this point is reached, then the object type is new, let's add it
        ObjectTimeInfo timeInfo = new ObjectTimeInfo();
        timeInfo.obj = obj;
        timeInfo.timeToNextUpdateMs = 0;
        timeInfo.updatePeriodMs = 0;
        objList.add(timeInfo);
    }

    /**
     * Update the object's timers
     */
    private synchronized void setUpdatePeriod(UAVObject obj, int periodMs)
    {
        // Find object type (not instance!) and update its period
    	ListIterator<ObjectTimeInfo> li = objList.listIterator();
    	while(li.hasNext()) {
    		ObjectTimeInfo n = li.next();
            if ( n.obj.getObjID() == obj.getObjID() )
            {
                n.updatePeriodMs = periodMs;
                n.timeToNextUpdateMs = (int) (periodMs * (new java.util.Random()).nextDouble()); // avoid bunching of updates
            }
        }
    }

    /**
     * Connect to all instances of an object depending on the event mask specified
     */
    private synchronized void connectToObjectInstances(UAVObject obj, int eventMask)
    {
        List<UAVObject> objs = objMngr.getObjectInstances(obj.getObjID());
        ListIterator<UAVObject> li = objs.listIterator();
        while(li.hasNext())
        {
        	obj = li.next();
            //TODO: Disconnect all
            // obj.disconnect(this);
        	
            // Connect only the selected events
            if ( (eventMask&EV_UNPACKED) != 0)
            {
            	obj.addUnpackedObserver(new Observer() {
					public void update(Observable observable, Object data) {
	            		objectUnpacked( (UAVObject) data);
	            	}            		
            	});
            }
            if ( (eventMask&EV_UPDATED) != 0)
            {
            	obj.addUpdatedAutoObserver(new Observer() {
					public void update(Observable observable, Object data) {
						objectUpdatedAuto( (UAVObject) data);
	            	}            		            		
            	});
            }
            if ( (eventMask&EV_UPDATED_MANUAL) != 0)
            {
            	obj.addUpdatedManualObserver(new Observer() {
					public void update(Observable observable, Object data) {
						objectUpdatedManual( (UAVObject) data);
	            	}            		            		
            	});
            }
            if ( (eventMask&EV_UPDATE_REQ) != 0)
            {
            	obj.addUpdateRequestedObserver(new Observer() {
					public void update(Observable observable, Object data) {
						updateRequested( (UAVObject) data);
	            	}            		
            	});
            }
        }
    }

    /**
     * Update an object based on its metadata properties
     */
    private synchronized void updateObject(UAVObject obj)
    {
        // Get metadata
        UAVObject.Metadata metadata = obj.getMetadata();

        // Setup object depending on update mode
        int eventMask;
        if ( metadata.gcsTelemetryUpdateMode == UAVObject.UpdateMode.UPDATEMODE_PERIODIC )
        {
            // Set update period
            setUpdatePeriod(obj, metadata.gcsTelemetryUpdatePeriod);
            // Connect signals for all instances
            eventMask = EV_UPDATED_MANUAL | EV_UPDATE_REQ;
            if(obj.isMetadata())
                eventMask |= EV_UNPACKED; // we also need to act on remote updates (unpack events)

            connectToObjectInstances(obj, eventMask);
        }
        else if ( metadata.gcsTelemetryUpdateMode == UAVObject.UpdateMode.UPDATEMODE_ONCHANGE )
        {
            // Set update period
            setUpdatePeriod(obj, 0);
            // Connect signals for all instances
            eventMask = EV_UPDATED | EV_UPDATED_MANUAL | EV_UPDATE_REQ;
            if(obj.isMetadata())
                eventMask |= EV_UNPACKED; // we also need to act on remote updates (unpack events)

            connectToObjectInstances(obj, eventMask);
        }
        else if ( metadata.gcsTelemetryUpdateMode == UAVObject.UpdateMode.UPDATEMODE_MANUAL )
        {
            // Set update period
            setUpdatePeriod(obj, 0);
            // Connect signals for all instances
            eventMask = EV_UPDATED_MANUAL | EV_UPDATE_REQ;
            if(obj.isMetadata())
                eventMask |= EV_UNPACKED; // we also need to act on remote updates (unpack events)

            connectToObjectInstances(obj, eventMask);
        }
        else if ( metadata.gcsTelemetryUpdateMode == UAVObject.UpdateMode.UPDATEMODE_NEVER )
        {
            // Set update period
            setUpdatePeriod(obj, 0);
            // Disconnect from object
            connectToObjectInstances(obj, 0);
        }
    }

    /**
     * Called when a transaction is successfully completed (uavtalk event)
     */
    private synchronized void transactionCompleted(UAVObject obj)
    {
    	if (DEBUG) Log.d(TAG,"UAVTalk transactionCompleted");
        // Check if there is a pending transaction and the objects match
        if ( transPending && transInfo.obj.getObjID() == obj.getObjID() )
        {
        	if (DEBUG) Log.d(TAG,"Telemetry: transaction completed for " + obj.getName());
            // Complete transaction
        	transTimer.cancel();
            transPending = false;
            // Send signal
            obj.transactionCompleted(true);
            // Process new object updates from queue
            processObjectQueue();
        } else
        {
        	Log.e(TAG,"Error: received a transaction completed when did not expect it.");
        }
    }

    /**
     * Called when a transaction is not completed within the timeout period (timer event)
     */
    private synchronized void transactionTimeout()
    {
    	if (DEBUG) Log.d(TAG,"Telemetry: transaction timeout.");
        transTimer.cancel();
        // Proceed only if there is a pending transaction
        if ( transPending )
        {
            // Check if more retries are pending
            if (transInfo.retriesRemaining > 0)
            {
                --transInfo.retriesRemaining;
                processObjectTransaction();
                ++txRetries;
            }
            else
            {
                // Terminate transaction
                utalk.cancelTransaction();
                transPending = false;
                // Send signal
                transInfo.obj.transactionCompleted(false);
                // Process new object updates from queue
                processObjectQueue();
                ++txErrors;
            }
        }
    }

    /**
     * Start an object transaction with UAVTalk, all information is stored in transInfo
     */
    private synchronized void processObjectTransaction()
    {
        if (transPending)
        {
        	if (DEBUG) Log.d(TAG, "Process Object transaction for " + transInfo.obj.getName());
            // Initiate transaction
            if (transInfo.objRequest)
            {
                utalk.sendObjectRequest(transInfo.obj, transInfo.allInstances);
            }
            else
            {
                utalk.sendObject(transInfo.obj, transInfo.acked == Acked.TRUE, transInfo.allInstances);
            }
            // Start timer if a response is expected
            if ( transInfo.objRequest || transInfo.acked == Acked.TRUE )
            {
            	transTimerSetPeriod(REQ_TIMEOUT_MS);
            }
            else
            {
            	transTimer.cancel();
                transPending = false;
            }
        } else
        {
        	Log.e(TAG,"Error: inside of processObjectTransaction with no transPending");
        }
    }

    /**
     * Process the event received from an object
     */
    private synchronized void processObjectUpdates(UAVObject obj, int event, boolean allInstances, boolean priority)
    {
        // Push event into queue
    	if (DEBUG) Log.d(TAG, "Push event into queue for obj " + obj.getName() + " event " + event);
    	if(event == 8 && obj.getName().compareTo("GCSTelemetryStats") == 0)
    		Thread.dumpStack();
        ObjectQueueInfo objInfo = new ObjectQueueInfo();
        objInfo.obj = obj;
        objInfo.event = event;
        objInfo.allInstances = allInstances;
        if (priority)
        {
            if ( objPriorityQueue.size() < MAX_QUEUE_SIZE )
            {
                objPriorityQueue.add(objInfo);
            }
            else
            {
                ++txErrors;
                obj.transactionCompleted(false);
                Log.w(TAG,"Telemetry: priority event queue is full, event lost " + obj.getName());
            }
        }
        else
        {
            if ( objQueue.size() < MAX_QUEUE_SIZE )
            {            
                objQueue.add(objInfo);
            }
            else
            {
                ++txErrors;
                obj.transactionCompleted(false);
            }
        }

        // If there is no transaction in progress then process event
        if (!transPending)
        {
            processObjectQueue();
        }
    }

    /**
     * Process events from the object queue
     */
    private synchronized void processObjectQueue()
    {
      	if (DEBUG) Log.d(TAG, "Process object queue - Depth " + objQueue.size() + " priority " + objPriorityQueue.size());

        // Don nothing if a transaction is already in progress (should not happen)
        if (transPending)
        {
        	Log.e(TAG,"Dequeue while a transaction pending");
            return;
        }

        // Get object information from queue (first the priority and then the regular queue)
        ObjectQueueInfo objInfo;
        if ( !objPriorityQueue.isEmpty() )
        {
            objInfo = objPriorityQueue.remove();
        }
        else if ( !objQueue.isEmpty() )
        {
            objInfo = objQueue.remove();
        }
        else
        {
            return;
        }

        // Check if a connection has been established, only process GCSTelemetryStats updates
        // (used to establish the connection)
        gcsStatsObj = objMngr.getObject("GCSTelemetryStats");
        if ( ((String) gcsStatsObj.getField("Status").getValue()).compareTo("Connected") != 0 )
        {
            objQueue.clear();
            if ( objInfo.obj.getObjID() != objMngr.getObject("GCSTelemetryStats").getObjID() )
            {
            	if (DEBUG) Log.d(TAG,"transactionCompleted(false) due to receiving object not GCSTelemetryStats while not connected.");
            	System.out.println(gcsStatsObj.toString());
            	System.out.println(objInfo.obj.toString());
                objInfo.obj.transactionCompleted(false);
                return;
            }
        }

        // Setup transaction (skip if unpack event)
        if ( objInfo.event != EV_UNPACKED )
        {
            UAVObject.Metadata metadata = objInfo.obj.getMetadata();
            transInfo.obj = objInfo.obj;
            transInfo.allInstances = objInfo.allInstances;
            transInfo.retriesRemaining = MAX_RETRIES;
            transInfo.acked = metadata.gcsTelemetryAcked;
            if ( objInfo.event == EV_UPDATED || objInfo.event == EV_UPDATED_MANUAL )
            {
                transInfo.objRequest = false;
            }
            else if ( objInfo.event == EV_UPDATE_REQ )
            {
                transInfo.objRequest = true;
            }
            // Start transaction
            transPending = true;
            processObjectTransaction();
        } else
        {
//            qDebug() << QString("Process object queue: this is an unpack event for %1").arg(objInfo.obj->getName());
        }

        // If this is a metaobject then make necessary telemetry updates
        if (objInfo.obj.isMetadata()) 
        {
        	UAVMetaObject metaobj = (UAVMetaObject) objInfo.obj;
            updateObject( metaobj.getParentObject() );
        }

        // The fact we received an unpacked event does not mean that
        // we do not have additional objects still in the queue,
        // so we have to reschedule queue processing to make sure they are not
        // stuck:
        if ( objInfo.event == EV_UNPACKED )
            processObjectQueue();

    }

    /**
     * Check is any objects are pending for periodic updates
     * TODO: Clean-up
     */
    private synchronized void processPeriodicUpdates()
    {
    	
    	if (DEBUG) Log.d(TAG, "processPeriodicUpdates()");
        // Stop timer
    	updateTimer.cancel();

        // Iterate through each object and update its timer, if zero then transmit object.
        // Also calculate smallest delay to next update (will be used for setting timeToNextUpdateMs)
        int minDelay = MAX_UPDATE_PERIOD_MS;
        ObjectTimeInfo objinfo;
        int elapsedMs = 0;
        long startTime;
        int offset;
        ListIterator<ObjectTimeInfo> li = objList.listIterator();
        while(li.hasNext())
        {
            objinfo = li.next();
            // If object is configured for periodic updates
            if (objinfo.updatePeriodMs > 0)
            {
                objinfo.timeToNextUpdateMs -= timeToNextUpdateMs;
                // Check if time for the next update
                if (objinfo.timeToNextUpdateMs <= 0)
                {
                    // Reset timer
                    offset = (-objinfo.timeToNextUpdateMs) % objinfo.updatePeriodMs;
                    objinfo.timeToNextUpdateMs = objinfo.updatePeriodMs - offset;
                    // Send object
                    startTime = System.currentTimeMillis();
                    processObjectUpdates(objinfo.obj, EV_UPDATED_MANUAL, true, false);
                    elapsedMs = (int) (System.currentTimeMillis() - startTime);
                    // Update timeToNextUpdateMs with the elapsed delay of sending the object;
                    timeToNextUpdateMs += elapsedMs;
                }
                // Update minimum delay
                if (objinfo.timeToNextUpdateMs < minDelay)
                {
                    minDelay = objinfo.timeToNextUpdateMs;
                }
            }
        }

        // Check if delay for the next update is too short
        if (minDelay < MIN_UPDATE_PERIOD_MS)
        {
            minDelay = MIN_UPDATE_PERIOD_MS;
        }

        // Done
        timeToNextUpdateMs = minDelay;

        // Restart timer
        updateTimerSetPeriod(timeToNextUpdateMs);
    }

    public TelemetryStats getStats()
    {
        // Get UAVTalk stats
        UAVTalk.ComStats utalkStats = utalk.getStats();

        // Update stats
        TelemetryStats stats = new TelemetryStats();
        stats.txBytes = utalkStats.txBytes;
        stats.rxBytes = utalkStats.rxBytes;
        stats.txObjectBytes = utalkStats.txObjectBytes;
        stats.rxObjectBytes = utalkStats.rxObjectBytes;
        stats.rxObjects = utalkStats.rxObjects;
        stats.txObjects = utalkStats.txObjects;
        stats.txErrors = utalkStats.txErrors + txErrors;
        stats.rxErrors = utalkStats.rxErrors;
        stats.txRetries = txRetries;

        // Done
        return stats;
    }

    public synchronized void resetStats()
    {
        utalk.resetStats();
        txErrors = 0;
        txRetries = 0;
    }

    private synchronized void objectUpdatedAuto(UAVObject obj)
    {
        processObjectUpdates(obj, EV_UPDATED, false, true);
    }

    private synchronized void objectUpdatedManual(UAVObject obj)
    {
        processObjectUpdates(obj, EV_UPDATED_MANUAL, false, true);
    }

    private synchronized void objectUnpacked(UAVObject obj)
    {
        processObjectUpdates(obj, EV_UNPACKED, false, true);
    }

    public synchronized void updateRequested(UAVObject obj)
    {
        processObjectUpdates(obj, EV_UPDATE_REQ, false, true);
    }

    private void newObject(UAVObject obj)
    {
        registerObject(obj);
    }

    private synchronized void newInstance(UAVObject obj)
    {
        registerObject(obj);
    }
    
	/**
	 * Private variables
	 */
	private TelemetryStats stats;
    private UAVObjectManager objMngr;
    private UAVTalk utalk;
    private UAVObject gcsStatsObj;
    private List<ObjectTimeInfo> objList = new ArrayList<ObjectTimeInfo>();
    private Queue<ObjectQueueInfo> objQueue = new LinkedList<ObjectQueueInfo>();
    private Queue<ObjectQueueInfo> objPriorityQueue = new LinkedList<ObjectQueueInfo>();
    private ObjectTransactionInfo transInfo = new ObjectTransactionInfo();
    private boolean transPending;
    
    private Timer updateTimer;
    private TimerTask updateTimerTask;
    private Timer transTimer;
    private TimerTask transTimerTask;
    
    private int timeToNextUpdateMs;
    private int txErrors;
    private int txRetries;
    
    /**
     * Private constants
     */
    private static final int REQ_TIMEOUT_MS = 250;
    private static final int MAX_RETRIES = 2;
    private static final int MAX_UPDATE_PERIOD_MS = 1000;
    private static final int MIN_UPDATE_PERIOD_MS = 1;
    private static final int MAX_QUEUE_SIZE = 20;
    


}
