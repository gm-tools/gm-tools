package gmtools.parsers;


import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer;
import org.openstreetmap.osmosis.core.domain.v0_6.Entity;
import org.openstreetmap.osmosis.core.domain.v0_6.Node;
import org.openstreetmap.osmosis.core.domain.v0_6.Relation;
import org.openstreetmap.osmosis.core.domain.v0_6.Tag;
import org.openstreetmap.osmosis.core.domain.v0_6.Way;
import org.openstreetmap.osmosis.core.domain.v0_6.WayNode;
import org.openstreetmap.osmosis.core.task.v0_6.RunnableSource;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;
import org.openstreetmap.osmosis.xml.common.CompressionMethod;
import org.openstreetmap.osmosis.xml.v0_6.XmlReader;

/**
 * copyright (c) 2014-2015 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk)
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 */
public class ParseOSM {
	public static final String TAG_KEY_AEROWAY = "aeroway";
	public static final String TAG_VALUE_TAXIWAY = "taxiway";
	public static final String TAG_VALUE_PARKPOS = "parking_position"; // sometimes used to mark stands rather than taxiway
	public static final String TAG_VALUE_RUNWAY = "runway";
	public static final String TAG_VALUE_GATE = "gate";

	public static final String TAG_KEY_REF = "ref";
	public static final String TAG_KEY_DISUSED = "disused";
	public static final String TAG_VALUE_YES = "yes";
	
	public static final String[] TO_STRIP_FROM_TAXIWAY_NAMES = {"TAXIWAY"}; // NB must be in uppercase
	
	/**all the nodes associated with taxiways or runways, with a list of the ways they are part of*/
	private Map<Long, List<AeroWay>> waysPerNode;
	
	/**all the ways representing taxiways*/
	private Set<AeroWay> ways;
	
	/**all the nodes in the OSM data, keyed by ID*/
	private Map<Long, Node> nodes;
	
	/**sometimes nodes are marked as "gate" - we can join those to taxiways the same as other locations; here, these are keyed by gate name/number*/
	private Map<String, Node> gateNodes;

	public ParseOSM(String filename) {
		waysPerNode = new TreeMap<Long, List<AeroWay>>();
		ways = new TreeSet<AeroWay>();
		nodes = new TreeMap<Long, Node>();
		gateNodes = new TreeMap<String, Node>();
		
		File file = new File(filename); // the input file

		Sink sinkImplementation = new Sink() {
			public void process(EntityContainer entityContainer) {
				Entity entity = entityContainer.getEntity();
				if (entity instanceof Node) {
					nodes.put(((Node)entity).getId(), (Node)entity);
					//System.out.println("Node:" + entity);
					
					boolean isGate = false;
					String name = null;
					Collection<Tag> tags = entity.getTags();
					for (Tag tag : tags) {
						String tagValue = tag.getValue();
						if (tag.getKey().equals(TAG_KEY_AEROWAY)) {
							isGate |= tagValue.equals(TAG_VALUE_GATE);
						}
						if (tag.getKey().equals(TAG_KEY_REF)) {
							name = tagValue.toUpperCase();
						}
					}
					
					if (isGate && (name != null)) {
						gateNodes.put(name, (Node)entity);
					}
				} else if (entity instanceof Way) {
					Collection<Tag> tags = entity.getTags();
					boolean isTaxiway = false;
					boolean isRunway = false;
					boolean isParkingPosition = false;
					boolean disused = false;
					String name = "E-" + entity.getId();
					for (Tag tag : tags) {
						String tagValue = tag.getValue();
						if (tag.getKey().equals(TAG_KEY_AEROWAY)) {
							isTaxiway |= tagValue.equals(TAG_VALUE_TAXIWAY);
							isRunway |= tagValue.equals(TAG_VALUE_RUNWAY);
							isParkingPosition |= tagValue.equals(TAG_VALUE_PARKPOS);
						}
						
						if (tag.getKey().equals(TAG_KEY_DISUSED) && tagValue.equals(TAG_VALUE_YES)) {
							disused = true;
						}
						
						if (tag.getKey().equals(TAG_KEY_REF)) {
							name = tagValue.toUpperCase();
							
							// clean name
							for (String s : TO_STRIP_FROM_TAXIWAY_NAMES) {
								name = name.replace(s, "");
							}
							name = name.trim(); // drop any remaining whitespace
						}
					}
					
					if ((isTaxiway || isRunway || isParkingPosition) && !disused) { // only interested in these nodes
						// now get all the nodes for this way and associate it with them
						AeroWay aw = new AeroWay((Way)entity, (isTaxiway ? AeroWay.Type.TAXIWAY : (isRunway ? AeroWay.Type.RUNWAY : (isParkingPosition ? AeroWay.Type.STAND : AeroWay.Type.OTHER))), name);
						ways.add(aw);
						for (WayNode wn : ((Way)entity).getWayNodes()) {
							List<AeroWay> l = waysPerNode.get(wn.getNodeId());
							if (l == null) {
								l = new ArrayList<AeroWay>();
								waysPerNode.put(wn.getNodeId(), l);
							}
							
							l.add(aw);
						}
					}
					
				} else if (entity instanceof Relation) {
					/* do nothing currently */
				}
			}
			public void release() { }
			public void complete() { }
			public void initialize(Map<String, Object> arg0) {}
		};

		boolean pbf = false;
		CompressionMethod compression = CompressionMethod.None;

		if (file.getName().endsWith(".pbf")) {
			pbf = true;
		} else if (file.getName().endsWith(".gz")) {
			compression = CompressionMethod.GZip;
		} else if (file.getName().endsWith(".bz2")) {
			compression = CompressionMethod.BZip2;
		}

		RunnableSource reader = null;

		try {
			if (pbf) {
				reader = new crosby.binary.osmosis.OsmosisReader(
						new FileInputStream(file));
			} else {
				reader = new XmlReader(file, false, compression);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		reader.setSink(sinkImplementation);

		Thread readerThread = new Thread(reader);
		readerThread.start();

		while (readerThread.isAlive()) {
			try {
				readerThread.join();
			} catch (InterruptedException e) {
				/* do nothing */
			}
		}
	}
	
	public Map<Long, List<AeroWay>> getWayNodes() {
		return waysPerNode;
	}
	
	public Set<AeroWay> getWays() {
		return ways;
	}
	
	public Node getNode(long nodeID) {
		return nodes.get(nodeID);
	}
	
	/**keyed by gate name/number*/
	public Map<String, Node> getGateNodes() {
		return gateNodes;
	}
	
	/**
	 * this is a simplification of the generic OSM Way class - just makes things a bit more accessible
	 * implements Comparable so we can maintain ordering
	 * */
	public static class AeroWay implements Comparable<AeroWay> {
		public enum Type {TAXIWAY, RUNWAY, STAND, OTHER}
		public Type type;
		public String name;
		public Way way;
		
		public AeroWay(Way way, Type type, String name) {
			this.way = way;
			this.type = type;
			this.name = name;
		}

		@Override
		public int compareTo(AeroWay arg0) {
			return this.way.compareTo(arg0.way);
		}
		
		@Override
		public boolean equals(Object obj) {
			return this.compareTo((AeroWay)obj) == 0;
		}
		
		@Override
		public int hashCode() {
			return this.way.hashCode();
		}
	}
}
