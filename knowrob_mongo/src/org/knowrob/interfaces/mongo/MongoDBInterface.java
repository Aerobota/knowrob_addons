package org.knowrob.interfaces.mongo;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.vecmath.Matrix4d;

import org.knowrob.interfaces.mongo.types.Designator;
import org.knowrob.interfaces.mongo.types.ISODate;
import org.knowrob.interfaces.mongo.types.PoseStamped;
import org.knowrob.tfmemory.TFMemory;

import ros.communication.Time;
import tfjava.Stamped;
import tfjava.StampedTransform;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.QueryBuilder;


public class MongoDBInterface {

	MongoClient mongoClient;
	DB db;

	TFMemory mem;

	/**
	 * Constructor
	 *
	 * Initialize DB client and connect to database.
	 *
	 */
	public MongoDBInterface() {

		try {
			mongoClient = new MongoClient( "localhost" , 27017 );
			db = mongoClient.getDB("roslog");

		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		mem = TFMemory.getInstance();
	}


	/**
	 * Wrapper around the lookupTransform method of the TFMemory class
	 *
	 * @param sourceFrameId ID of the source frame of the transformation
	 * @param targetFrameId ID of the target frame of the transformation
	 * @param posix_ts POSIX timestamp (seconds since 1.1.1970)
	 * @return
	 */
	public StampedTransform lookupTransform(String targetFrameId, String sourceFrameId, int posix_ts) {
		Time t = new Time();
		t.secs = posix_ts;
		return(mem.lookupTransform(targetFrameId, sourceFrameId, t));
	}

	/**
	 * Wrapper around the transformPose method of the TFMemory class
	 *
	 * @param targetFrameID  ID of the target frame of the transformation
	 * @param stampedIn      Stamped<Matrix4d> with the pose in the original coordinates
	 * @param stampedOut     Stamped<Matrix4d> that will hold the resulting pose
	 * @return               true if transform succeeded
	 */
	public boolean transformPose(String targetFrameID, Stamped<Matrix4d> stampedIn, Stamped<Matrix4d> stampedOut) {
		return mem.transformPose(targetFrameID, stampedIn, stampedOut);
	}


	/**
	 * Read designator value from either the uima_uima_results collection
	 * or the logged_designators collection.
	 *
	 * @param designator Designator ID to be read
	 * @return Instance of a Designator
	 */
	public Designator getDesignatorByID(String designator) {

		for(String db_name : new String[]{"uima_uima_results", "logged_designators"}) {

			DBCollection coll = db.getCollection(db_name);
			DBObject query = new QueryBuilder()
								.or(QueryBuilder.start("designator.__id").is(designator).get(),
									QueryBuilder.start("designator.__ID").is(designator).get()).get();

			DBObject cols  = new BasicDBObject();
			cols.put("designator", 1 );

			DBCursor cursor = coll.find(query, cols);

			while(cursor.hasNext()) {
				DBObject row = cursor.next();

				Designator desig = new Designator().readFromDBObject((BasicDBObject) row.get("designator"));

				// set the event type (i.e. perception, sth else) to store
				// which kind of information is described in the designator
				if(db_name.equals("uima_uima_results"))
					desig.setDetectionType("VisualPerception");
				else
					desig.setDetectionType("MentalEvent");

				return desig;
			}
			cursor.close();
		}
		return null;
	}


	/**
	 * Read the latest perception before the time point identified by posix_ts
	 *
	 * @param posix_ts Time stamp in POSIX format (seconds since 1.1.1970)
	 * @return Designator object returned by the last perception before that time
	 */
	public Designator latestUIMAPerceptionBefore(int posix_ts) {

		Designator desig = null;
		DBCollection coll = db.getCollection("uima_uima_results");

		// read all events up to one minute before the time
		Date start = new ISODate((long) 1000 * (posix_ts - 60) ).getDate();
		Date end   = new ISODate((long) 1000 * (posix_ts + 60) ).getDate();

		DBObject query = QueryBuilder
				.start("__recorded").greaterThanEquals( start )
				.and("__recorded").lessThan( end ).get();

		DBObject cols  = new BasicDBObject();
		cols.put("designator", 1 );

		DBCursor cursor = coll.find(query, cols);
		cursor.sort(new BasicDBObject("__recorded", -1));
		try {
			while(cursor.hasNext()) {
				DBObject row = cursor.next();
				desig = new Designator().readFromDBObject((BasicDBObject) row.get("designator"));
				break;
			}
		} catch(Exception e){
			e.printStackTrace();
		} finally {
			cursor.close();
		}
		return desig;
	}


	/**
	 * Get all times when an object has been detected
	 *
	 * @param object
	 * @return
	 */
	public List<Date> getUIMAPerceptionTimes(String object) {
		List<Date> times = new ArrayList<Date>();
		DBCollection coll = db.getCollection("uima_uima_results");

		// TODO: This will always return a single result since the ID is unique
		DBObject query = QueryBuilder
				.start("designator.__id").is(object).get();

		DBObject cols  = new BasicDBObject();
		cols.put("__recorded", 1 );

		DBCursor cursor = coll.find(query, cols);
		cursor.sort(new BasicDBObject("__recorded", -1));
		try {
			while(cursor.hasNext()) {

				DBObject row = cursor.next();
				Date currentTime = (new ISODate(0).readFromDBObject((BasicDBObject) row.get("__recorded"))).getDate();
				times.add(currentTime);

			}
		} catch(Exception e){
			e.printStackTrace();
		} finally {
			cursor.close();
		}
		return times;
	}


	/**
	 *
	 * @param posix_ts
	 * @return
	 */
	public List<String> getUIMAPerceptionObjects(int posix_ts) {

		Designator res = null;

		Date start = new ISODate((long) 1000 * (posix_ts - 30) ).getDate();
		Date end   = new ISODate((long) 1000 * (posix_ts + 30) ).getDate();

		List<String> objects = new ArrayList<String>();
		DBCollection coll = db.getCollection("uima_uima_results");

		DBObject query = QueryBuilder
				.start("__recorded").greaterThanEquals( start )
				.and("__recorded").lessThan( end ).get();


		DBObject cols  = new BasicDBObject();
		cols.put("designator", 1 );

		DBCursor cursor = coll.find(query, cols);
		cursor.sort(new BasicDBObject("__recorded", -1));
		try {
			while(cursor.hasNext()) {

				DBObject row = cursor.next();
				res = new Designator().readFromDBObject((BasicDBObject) row.get("designator"));
				objects.add((String)res.get("__id"));

			}
		} catch(Exception e){
			e.printStackTrace();
		} finally {
			cursor.close();
		}
		return objects;
	}


	public Matrix4d getDesignatorLocation(String id) {
		Matrix4d poseMatrix = null;
		DBCollection coll = db.getCollection("logged_designators");
		DBObject query = QueryBuilder
				.start("designator.__ID").is(id).get();

		DBObject cols  = new BasicDBObject();
		cols.put("__recorded", 1 );
		cols.put("designator", 1 );

		DBCursor cursor = coll.find(query, cols);
		cursor.sort(new BasicDBObject("__recorded", -1));
		try {
			while(cursor.hasNext()) {

				DBObject row = cursor.next();
				Designator res = new Designator().readFromDBObject((BasicDBObject) row.get("designator"));
				PoseStamped pose_stamped = (PoseStamped)res.get("POSE");
				poseMatrix = pose_stamped.getMatrix4d();
				break;

			}
		} catch(Exception e){
			e.printStackTrace();
		} finally {
			cursor.close();
		}
		return poseMatrix;
	}

	public static void main(String[] args) {

		MongoDBInterface m = new MongoDBInterface();


		// test transformation lookup based on DB information

//		Timestamp timestamp = Timestamp.valueOf("2013-07-26 14:27:22.0");
//		Time t = new Time(1377766521);
//		Time t = new Time(1383143712); // no
//		Time t = new Time(1383144279);  //1



		Time t_st  = new Time(1392799358);
		Time t_end = new Time(1392799363);

		long t0 = System.nanoTime();
		TFMemory tf = TFMemory.getInstance();
		System.out.println(tf.lookupTransform("/base_link", "/l_gripper_palm_link", t_end));
		long t1 = System.nanoTime();
		System.out.println(tf.lookupTransform("/base_link", "/l_gripper_palm_link", t_end));
		long t2 = System.nanoTime();
		System.out.println(tf.lookupTransform("/base_link", "/l_gripper_palm_link", t_st));
		long t3 = System.nanoTime();

		double first  = (t1-t0)/ 1E6;
		double second = (t2-t1)/ 1E6;
		double third  = (t3-t2)/ 1E6;

		System.out.println("Time to look up first transform: " + first + "ms");
		System.out.println("Time to look up second transform: " + second + "ms");
		System.out.println("Time to look up second transform: " + third + "ms");

		// test lookupTransform wrapper
//		trans = m.lookupTransform("/map", "/head_mount_kinect_ir_link", 1377766521);
//		System.out.println(trans);

//		// test UIMA result interface
//		Designator d = m.latestUIMAPerceptionBefore(1377766521);
//		System.out.println(d);
//
//		// test designator reading
//		d = m.getDesignatorByID("designator_bunEaUUmPbuoLN");
//		System.out.println(d);
	}
}

