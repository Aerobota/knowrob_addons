/* 
 * Copyright (c) 2013 Moritz Tenorth, Sjoerd van den Dries
 * 
 * Based on the TFListener class by Sjoerd v.d. Dries in tfjava
 * 
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the Technische Universiteit Eindhoven nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 */

package org.knowrob.interfaces.mongo;

import ros.communication.*;

import ros.pkg.geometry_msgs.msg.TransformStamped;
import tfjava.Frame;
import tfjava.Stamped;
import tfjava.StampedTransform;
import tfjava.TimeCache;
import tfjava.TransformStorage;

import javax.vecmath.Quat4d;
import javax.vecmath.Vector3d;
import javax.vecmath.Point3d;
import javax.vecmath.Matrix4d;

import org.knowrob.interfaces.mongo.util.ISO8601Date;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.QueryBuilder;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.LinkedList;

/**
 * A client that reads transforms from the DB, stores them in a buffer and allows transformation
 * lookups from one frame to another.
 * 
 * All tf messages published on the /tf topic are stored in
 * a buffer, first sorted by child frame, then by parent frame, then by time stamp. This allows fast
 * lookup of transformations. Tf's that are MAX_STORAGE_TIME older than the newest tf in the corresponding
 * time cache are ignored.
 * 
 * To calculate a transformation from some source frame S to a target frame T at time t, TFListener uses a graph
 * search to find the best path from S to T. At the moment, 'best' means that the largest difference between
 * the time stamps of the transformations on the path and time t is minimized. If the tf graph is a tree, as is
 * the case with original C++-implementation of tf, the graph will simply return the only path available (if any).
 * 
 * TFlistener is implemented as a singleton, which guarantees that at any time at most one client per system is
 * listening to the /tf topic.
 * 
 * @author Sjoerd van den Dries, Moritz Tenorth
 * @version March 4, 2011
 */
public class TFMemory {

	/** Maximum buffer storage time */
	public static final long MAX_STORAGE_TIME = (new Duration(10, 0)).totalNsecs(); 

	/** The singleton instance */
	protected static TFMemory instance;

	/** Map that maps frame IDs (names) to frames */    
	protected HashMap<String, Frame> frames;

	/** TF name prefix, currently not used (TODO) */
	protected String tfPrefix = "";

	// duration through which transforms are to be kept in the buffer
	protected final static int BUFFER_SIZE = 5;

	MongoClient mongoClient;
	DB db;

	/* **********************************************************************
	 * *                           INITIALIZATION                           *
	 * ********************************************************************** */ 

	/**
	 * Returns the TFListener instance.
	 */    
	public synchronized static TFMemory getInstance() {

		if (instance == null) {
			instance = new TFMemory();
		}
		return instance;
	}

	/**
	 * Class constructor.
	 */    
	protected TFMemory() {

		try {
			mongoClient = new MongoClient( "localhost" , 27017 );
			db = mongoClient.getDB("roslog-pr2");
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}

		frames = new HashMap<String, Frame>();    

	}	


	/* **********************************************************************
	 * *                            TF LISTENER                             *
	 * ********************************************************************** */	

	protected boolean setTransforms(String json_transforms) {

		JSONArray tfs = JSONArray.fromObject(json_transforms);


		for (int i = 0; i < tfs.size(); i++) {

			setTransform(tfs.getJSONObject(i));


		}


		return true;
	}

	/**
	 * Converts transform (a geometry msg) to a TransformStorage object and adds it to the buffer.
	 */    
	protected boolean setTransform(JSONObject tf_stamped) {

		// read JSON string
		JSONObject json_header = tf_stamped.getJSONObject("header");
		JSONObject json_transform = tf_stamped.getJSONObject("transform");

		// resolve the frame ID's
		String childFrameID = assertResolved(tfPrefix, tf_stamped.getString("child_frame_id"));
		String frameID = assertResolved(tfPrefix, json_header.getString("frame_id"));


		boolean errorExists = false;
		if (childFrameID == frameID) {
			System.err.println("TF_SELF_TRANSFORM: Ignoring transform with frame_id and child_frame_id  \"" + childFrameID + "\" because they are the same");
			errorExists = true;
		}

		if (childFrameID == "/") { //empty frame id will be mapped to "/"
			System.err.println("TF_NO_CHILD_FRAME_ID: Ignoring transform because child_frame_id not set ");
			errorExists = true;
		}

		if (frameID == "/") { //empty parent id will be mapped to "/"
			System.err.println("TF_NO_FRAME_ID: Ignoring transform with child_frame_id \"" + childFrameID + "\" because frame_id not set");
			errorExists = true;
		}

		if (errorExists) return false;	    

		// lookup or insert child frame
		Frame frame = lookupOrInsertFrame(childFrameID);

		// convert tf message to JTransform datastructure
		double trans_x = json_transform.getJSONObject("translation").getDouble("x");
		double trans_y = json_transform.getJSONObject("translation").getDouble("y");
		double trans_z = json_transform.getJSONObject("translation").getDouble("z");

		double rot_w = json_transform.getJSONObject("rotation").getDouble("w");
		double rot_x = json_transform.getJSONObject("rotation").getDouble("x");
		double rot_y = json_transform.getJSONObject("rotation").getDouble("y");
		double rot_z = json_transform.getJSONObject("rotation").getDouble("z");

		// add frames to map
		Frame childFrame = lookupOrInsertFrame(childFrameID);
		Frame parentFrame = lookupOrInsertFrame(frameID);


		ISO8601Date timestamp = new ISO8601Date(json_header.getJSONObject("stamp").getString("$date"));

		TransformStorage tf = new TransformStorage(new Vector3d(trans_x, trans_y, trans_z),
				new Quat4d(rot_x, rot_y, rot_z, rot_w),
				timestamp.getNanoSeconds(),
				parentFrame, childFrame);


		// try to insert tf in corresponding time cache. If result is FALSE, the tf contains old data.
		if (!frame.insertData(tf)) {
			System.err.println("TF_OLD_DATA ignoring data from the past for frame \"" + childFrameID + "\" at time " + ((double)tf.getTimeStamp() / 1E9));
			return false;
		}

		return true;
	}

	/**
	 * Looks up and returns the frame belonging to the given frame ID.
	 * If the frame does not exist yet, it is first added to the map.
	 */    
	protected Frame lookupOrInsertFrame(String frameID) {
		Frame frame = frames.get(frameID);
		if (frame == null) {
			frame = new Frame(frameID, MAX_STORAGE_TIME);
			frames.put(frameID, frame);
		}	    
		return frame;
	}


	/* **********************************************************************
	 * *                         TRANSFORM METHODS                          *
	 * ********************************************************************** */ 

	/**
	 * Transforms a stamped point to the given target frame, and returns the result in stampedOut.
	 */	
	public void transformPoint(String targetFrameID, Stamped<Point3d> stampedIn, Stamped<Point3d> stampedOut) {
		StampedTransform transform = lookupTransform(targetFrameID, stampedIn.frameID, stampedIn.timeStamp);
		transform.transformPoint(stampedIn.getData(), stampedOut.getData());
		stampedOut.frameID = targetFrameID;
		stampedOut.timeStamp = stampedIn.timeStamp;
	}

	/**
	 * Transforms a stamped point to the given target frame and time, based on a given fixed frame, and
	 * returns the result in stampedOut.
	 */	
	public void transformPoint(String targetFrameID, Time targetTime, Stamped<Point3d> stampedIn,
			String fixedFrameID, Stamped<Point3d> stampedOut) {
		StampedTransform transform = lookupTransform(targetFrameID, targetTime, stampedIn.frameID, stampedIn.timeStamp, fixedFrameID);
		transform.transformPoint(stampedIn.getData(), stampedOut.getData()); 
		stampedOut.frameID = targetFrameID;
		stampedOut.timeStamp = stampedIn.timeStamp;
	}
	/**
	 * Transforms a stamped pose to the given target frame, and returns the result in stampedOut.
	 */ 	
	public void transformPose(String targetFrameID, Stamped<Matrix4d> stampedIn, Stamped<Matrix4d> stampedOut) {
		StampedTransform transform = lookupTransform(targetFrameID, stampedIn.frameID, stampedIn.timeStamp);
		transform.transformPose(stampedIn.getData(), stampedOut.getData());	    
		stampedOut.frameID = targetFrameID;
		stampedOut.timeStamp = stampedIn.timeStamp;
	}

	/**
	 * Transforms a stamped pose to the given target frame and time, based on a given fixed frame, and
	 * returns the result in stampedOut.
	 */ 	
	public void transformPose(String targetFrameID, Time targetTime, Stamped<Matrix4d> stampedIn,
			String fixedFrameID, Stamped<Matrix4d> stampedOut) {
		StampedTransform transform = lookupTransform(targetFrameID, targetTime, stampedIn.frameID, stampedIn.timeStamp, fixedFrameID);
		transform.transformPose(stampedIn.getData(), stampedOut.getData());
		stampedOut.frameID = targetFrameID;
		stampedOut.timeStamp = stampedIn.timeStamp;
	}

	/* **********************************************************************
	 * *                          LOOKUP METHODS                            *
	 * ********************************************************************** */    

	/**
	 * Returns the transform from the specified source frame to the target frame at a given time; returns
	 * null if no transformation could be found.
	 */
	public StampedTransform lookupTransform(String targetFrameID, String sourceFrameID, Time time) {

		// resolve the source and target IDs
		String resolvedTargetID = assertResolved(tfPrefix, targetFrameID);
		String resolvedSourceID = assertResolved(tfPrefix, sourceFrameID);

		// if source and target are the same, return the identity transform
		if (resolvedSourceID == resolvedTargetID) {
			StampedTransform out = StampedTransform.getIdentity();
			out.timeStamp = time;
			out.frameID = resolvedSourceID;
			out.childFrameID = resolvedTargetID;            
			return out;
		}



		// load data from DB if the current time point is already in the buffer
		Frame sourceFrame = verifyDataAvailable(time, resolvedSourceID);
		Frame targetFrame = verifyDataAvailable(time, resolvedTargetID);
		
		
		// list that will contain transformations from source frame to some frame F        
		LinkedList<TransformStorage> inverseTransforms = new LinkedList<TransformStorage>();
		// list that will contain transformations from frame F to target frame
		LinkedList<TransformStorage> forwardTransforms = new LinkedList<TransformStorage>();

		// fill the lists using lookupLists. If it returns FALSE, no transformation could be found.
		if (!lookupLists(targetFrame, sourceFrame, time.totalNsecs(), inverseTransforms, forwardTransforms)) {
			// TODO give warning
			System.err.println("Cannot transform: source + \"" + resolvedSourceID + "\" and target \""
					+ resolvedTargetID + "\" are not connected.");
			return null;
		}        

		// create an identity transform with the correct time stamp
		StampedTransform out = StampedTransform.getIdentity();	    
		out.timeStamp = time;

		// multiply all transforms from source frame to frame F TODO: right?
		for(TransformStorage t : inverseTransforms) {           
			out.mul(StorageToStampedTransform(t));
		}

		// multiply all transforms from frame F to target frame TODO: right?
		for(TransformStorage t : forwardTransforms) {	        
			out.mul(StorageToStampedTransform(t).invert(), out);
		}	    

		// return transform
		return out;
	}

	
	/**
	 * Check if there are transforms for this time and this frame in the buffer,
	 * try to load from DB otherwise (-> use DB only when needed)
	 * 
	 * @param time
	 * @param sourceFrame
	 */
	private Frame verifyDataAvailable(Time time, String frameID) {
		
		// lookup frame
		Frame frame = frames.get(frameID);

		boolean inside = false;

		if(frame!=null && frame.getParentFrames()!=null) {
			for(Frame f : frame.getParentFrames()) {

				if(frame.getTimeCache(f).timeInBufferRange(new ISO8601Date(time).getMilliSeconds())) {
					inside = true;
					break;
				}
			}

		} else if(frame!=null || !inside) {
			
			// load data from DB if frame is unknown or time not buffered yet
			loadTransformFromDB(frameID, new ISO8601Date(time).getDate());
			frame = frames.get(frameID);
		} 
		
		if(frame==null) {
			System.err.println("Cannot transform: source frame \"" + frameID + "\" does not exist.");
			return null;
		}    
		return frame;
	}


	/**
	 * Load transforms from the DB and add them to the local tf buffer
	 * 
	 * @param childFrameID
	 * @param date
	 * @return
	 */
	private StampedTransform loadTransformFromDB(String childFrameID, Date date) {

		DBCollection coll = db.getCollection("tf");
		DBObject query = new BasicDBObject();

		// select time slice from BUFFER_SIZE seconds before to one second after given time
		Date start = new Date(date.getTime()-BUFFER_SIZE * 1000);
		Date end   = new Date(date.getTime() + 1000);

		query = QueryBuilder.start("transforms")
				.elemMatch(new BasicDBObject("child_frame_id", childFrameID))
				.and("__recorded").greaterThanEquals( start )
				.and("__recorded").lessThan( end )
				.get();

		DBObject cols  = new BasicDBObject();
		cols.put("_id", 1 );
		cols.put("__recorded",  1 );
		cols.put("transforms",  1 );


		DBCursor cursor = coll.find(query, cols );
		cursor.sort(new BasicDBObject("__recorded", -1));


		StampedTransform res = null;
		try {
			int i = 0;
			while(cursor.hasNext()) {

				DBObject row = cursor.next();
				setTransforms(row.get("transforms").toString());

				break;
			}
		} finally {
			cursor.close();
		}
		return res;
	}





	/**
	 * Returns the transform from the specified source frame at sourceTime to the target frame at a given
	 * targetTime, based on a given fixed frame; returns null if no transformation could be found.
	 */
	public StampedTransform lookupTransform(String targetID, Time targetTime, String sourceID, Time sourceTime, String fixedID) {	    
		// lookup transform from source to fixed frame, at sourceTime
		StampedTransform t1 = lookupTransform(fixedID, sourceID, sourceTime);
		// lookup transform from fixed frame to target frame, at targetTime
		StampedTransform t2 = lookupTransform(targetID, fixedID, targetTime);

		// if either of the two transformations did not succeed, return null
		if (t1 == null || t2 == null) return null;	    

		// multiply transformation t2 with t1, and return
		t2.mul(t1);
		return t2;
	}

	/**
	 * Performs a bi-directional best-first graph search on the tf graph to try to find a path from sourceFrame
	 * to targetFrame, at the given time. One priority queue is used to keep a sorted list of all search nodes
	 * (from both directions, ordered descending by their potential of contributing to a good solution). At
	 * the moment, the cost of the path from A to B is defined as the largest absolute difference between the
	 * time stamps of the transforms from A to B and the given time point. This corresponds to searching for a
	 * transform path that needs the least amount of inter- and extrapolation.  
	 * 
	 * Note: often in search, if we talk about expanding a search node, we say that the node expands and its
	 * _children_ are added to the queue. Yet, the tf graph is stored by linking child frames to their _parent_
	 * frames, not the other way around. So, if a search node is expanded, the _parent_ frames are added to the
	 * queue. This may be a bit confusing.
	 */	
	protected boolean lookupLists(Frame targetFrame, Frame sourceFrame, long time,
			LinkedList<TransformStorage> inverseTransforms, LinkedList<TransformStorage> forwardTransforms) {

		// wrap the source and target frames in search nodes
		SearchNode<Frame> sourceNode = new SearchNode<Frame>(sourceFrame);
		SearchNode<Frame> targetNode = new SearchNode<Frame>(targetFrame);

		// set beginning of forward path (from source)
		sourceNode.backwardStep = sourceNode;
		// set beginning of backward path (form target)
		targetNode.forwardStep = targetNode;        

		// create a hash map that map frames to search nodes. This is necessary to keep track of
		// which frames have already been visited (and from which direction).
		HashMap<Frame, SearchNode<Frame>> frameToNode = new HashMap<Frame, SearchNode<Frame>>();

		// add source and target search nodes to the map
		frameToNode.put(sourceFrame, sourceNode);
		frameToNode.put(targetFrame, targetNode);

		// create a priority queue, which will hold the search nodes ordered by cost (descending)
		PriorityQueue<SearchNode<Frame>> Q = new PriorityQueue<SearchNode<Frame>>();

		// at the source and target search nodes to the queue
		Q.add(sourceNode); 
		Q.add(targetNode);

		// perform the search
		while(!Q.isEmpty()) {
			// poll most potential search node from queue
			SearchNode<Frame> frameNode = Q.poll();
			Frame frame = frameNode.content;

			// if the node is both visited from the source and from the target node, a path has been found
			if (frameNode.backwardStep != null && frameNode.forwardStep != null) {
				// found the best path from source to target through FRAME.

				// create inverse list (from source to FRAME)
				SearchNode<Frame> node = frameNode;                
				while(node.content != sourceNode.content) {                    
					inverseTransforms.addLast(node.backwardStep.content.getData(time, node.content));
					node = node.backwardStep;
				}

				// create forward list (from FRAME to target)
				node = frameNode;
				while(node.content != targetNode.content) {
					forwardTransforms.addLast(node.forwardStep.content.getData(time, node.content));
					node = node.forwardStep;
				}
				return true;
			}

			// expand search node
			for(Frame parentFrame : frame.getParentFrames()) {                
				SearchNode<Frame> parentFrameNode = frameToNode.get(parentFrame);

				boolean addToQueue = false;
				if (parentFrameNode == null) {
					// node was not yet visited
					parentFrameNode = new SearchNode<Frame>(parentFrame);                    
					frameToNode.put(parentFrame, parentFrameNode);
					addToQueue = true;
				} else {
					// node is already visited
					if ((parentFrameNode.backwardStep == null && frameNode.forwardStep == null)
							|| (parentFrameNode.forwardStep == null && frameNode.backwardStep == null)) {
						// node was visited, but from other direction.
						// create new search node that represents this frame, visited from both sides
						// this allows the other search node of this frame to still be expanded first                        
						parentFrameNode = new SearchNode<Frame>(parentFrameNode);
						addToQueue = true;                        
					}
				}                

				// add search node belonging to parent frame to the queue
				if (addToQueue) {
					// determine cost (based on max absolute difference in time stamp) 
					TimeCache cache = frame.getTimeCache(parentFrame);
					parentFrameNode.cost = Math.max((double)cache.timeToNearestTransform(time),
							Math.max(parentFrameNode.cost, frameNode.cost));
					// if visiting forward (from source), set backward step to remember path 
					if (frameNode.backwardStep != null) parentFrameNode.backwardStep = frameNode;
					// if visiting backward (from target), set forward step to remember path
					if (frameNode.forwardStep != null) parentFrameNode.forwardStep = frameNode;
					// add node to queue
					Q.add(parentFrameNode);
				}
			}
		}    

		// target and source frames are not connected.        
		return false;
	}

	/**
	 * Wrapper search node that can be used for bi-directional best-first search. 
	 * Keeps track of search path by maintaining links to parent nodes, in both directions
	 * (i.e., from source and from target node).
	 * 
	 * @author Sjoerd van den Dries
	 * @param <V> Content type of the search node
	 */    
	protected class SearchNode<V> implements Comparable<SearchNode<V>> {        
		/** Content of search node */
		V content;
		/** Cost of path up and until this search node */
		double cost;
		/** Refers to parent node in forward path */
		SearchNode<V> backwardStep;
		/** Refers to parent node in backward path */
		SearchNode<V> forwardStep;        

		/** Default constructor; sets specified content and cost to 0, steps to null. */
		SearchNode(V content) {
			this.content = content;
			this.cost = 0;
			this.backwardStep = null;
			this.forwardStep = null;
		}

		/** Copy constructor */
		SearchNode(SearchNode<V> orig) {
			this.content = orig.content;
			this.cost = orig.cost;
			this.backwardStep = orig.backwardStep;
			this.forwardStep = orig.forwardStep;
		}

		/** Comparator method: low cost < high cost. */
		public int compareTo(SearchNode<V> other) {
			if (this.cost < other.cost) return -1;
			if (this.cost > other.cost) return 1;
			return 0;
		}        

	}

	/* **********************************************************************
	 * *                          HELPER METHODS                            *
	 * ********************************************************************** */	

	//	/**
	//	 * Converts the given TransformStamped message to the TransformStorage datastructure
	//	 */	
	//	protected TransformStorage transformStampedMsgToTF(TransformStamped msg) {
	//	    ros.pkg.geometry_msgs.msg.Vector3 tMsg = msg.transform.translation;
	//	    ros.pkg.geometry_msgs.msg.Quaternion rMsg = msg.transform.rotation;	
	//	    
	//	    // add frames to map
	//	    Frame childFrame = lookupOrInsertFrame(msg.child_frame_id);
	//	    Frame parentFrame = lookupOrInsertFrame(msg.header.frame_id);
	//	    
	//	    return new TransformStorage(new Vector3d(tMsg.x, tMsg.y, tMsg.z),
	//	                                new Quat4d(rMsg.x, rMsg.y, rMsg.z, rMsg.w),
	//	                                msg.header.stamp.totalNsecs(),
	//	                                parentFrame, childFrame);
	//	}

	/**
	 * Converts the given TransformStorage datastructure to a TransformStamped message
	 */ 	
	protected TransformStamped TFToTransformStampedMsg(TransformStorage tf) {
		Vector3d tTF = tf.getTranslation();
		Quat4d rTF = tf.getRotation();

		// convert quaternion and translation vector to corresponding messages
		ros.pkg.geometry_msgs.msg.Vector3 tMsg = new ros.pkg.geometry_msgs.msg.Vector3();
		ros.pkg.geometry_msgs.msg.Quaternion rMsg = new ros.pkg.geometry_msgs.msg.Quaternion();
		tMsg.x = tTF.x; tMsg.y = tTF.y; tMsg.z = tTF.z;
		rMsg.x = rTF.x; rMsg.y = rTF.y; rMsg.z = rTF.z; rMsg.w = rTF.w;

		// create TransformStamped message
		TransformStamped msg = new TransformStamped();	    
		msg.header.frame_id = tf.getParentFrame().getFrameID();
		msg.header.stamp = new Time(tf.getTimeStamp());
		msg.child_frame_id = tf.getChildFrame().getFrameID();
		msg.transform.translation = tMsg;
		msg.transform.rotation = rMsg;

		return msg;
	}

	/**
	 * Converts the TransformStorage datastructure (represented by quaternion and vector) to
	 * the StampedTransform datastructure (represented by a 4x4 matrix)
	 */
	protected StampedTransform StorageToStampedTransform(TransformStorage ts) {
		return new StampedTransform(ts.getTranslation(), ts.getRotation(), new Time(ts.getTimeStamp()), 
				ts.getParentFrame().getFrameID(), ts.getChildFrame().getFrameID());
	}

	/**
	 * Returns the resolved version of the given frame ID, and asserts a debug message if the name
	 * was not fully resolved.
	 */ 	
	private String assertResolved(String prefix, String frameID) {
		if (!frameID.startsWith("/"))	    
			System.err.println("TF operating on not fully resolved frame id " + frameID +", resolving using local prefix " + prefix);
		return resolve(prefix, frameID);
	}

	/**
	 * Returns the resolves version of the given frame ID.
	 */	
	private static String resolve(String prefix, String frameID)	{			    
		if (frameID.startsWith("/")) {
			return frameID;			
		}	  

		if (prefix.length() > 0) {
			if (prefix.startsWith("/")) {
				return prefix + "/" + frameID;
			} else {
				return "/" + prefix + "/" + frameID;
			}
		}  else {		    
			return "/" + frameID;			
		}
	}	

	/* Returns the tf prefix from the parameter list
	 * 
	 * TODO: does not work yet
	private static String getPrefixParam(NodeHandle nh) {
		String param; 
		if (!nh.hasParam("tf_prefix")) return ""; 

		try {
			return nh.getStringParam("tf_prefix", false);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return "";
	}
	 */	

}
