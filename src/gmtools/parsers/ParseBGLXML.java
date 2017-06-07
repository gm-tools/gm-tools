package gmtools.parsers;

import java.io.FileInputStream;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import gmtools.graph.TaxiNode;
import gmtools.graph.TaxiNode.NodeType;

/**
 * Parse an XML file, generated from BGL by ScruffyDucks BGL2XML software
 * Just now only reads the stands. End goal is full parsing of the layout. 
 */
public class ParseBGLXML {
	private Map<String, TaxiNode> standNodes;
	
//	
//	public static void main(String[] args) {
//		new ParseBGLXML("C:\\sb\\Dropbox\\Research\\TRANSIT\\Frankfurt\\pwi_eddf_2014\\EDDF_ADE9_PWI.BGL.xml");
//	}
	
	public ParseBGLXML(String filename) {
		standNodes = new TreeMap<>();
		
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document document = builder.parse(new FileInputStream(filename));

			Element root = document.getDocumentElement();
			NodeList airports = root.getChildNodes();
			for (int i1 = 0; i1 < airports.getLength(); i1++) {
				Node n1 = airports.item(i1);
				if (n1.getNodeName().equals("Airport")) {
					NodeList taxiwayParkings = n1.getChildNodes();
					for (int i2 = 0; i2 < taxiwayParkings.getLength(); i2++) {
						Node n2 = taxiwayParkings.item(i2);
						if (n2.getNodeName().equals("TaxiwayParking")) {
							NamedNodeMap a = n2.getAttributes();
							
							String strLat = a.getNamedItem("lat").getNodeValue();
							String strLon = a.getNamedItem("lon").getNodeValue();
							String strName = a.getNamedItem("name").getNodeValue(); // seems to be apron prefix most often
							String strNum = a.getNamedItem("number").getNodeValue();
							
							//System.out.println(strLat + " " + strLon + " " + strName + " " + strNum);
	
							double lat = Double.parseDouble(strLat);
							double lon = Double.parseDouble(strLon);
							String name = strName + "-" + strNum;
							
							// deliberately not setting node attachments and associated taxiways as we don't know them here
							TaxiNode tn = new TaxiNode("S-" + name, NodeType.STAND, lat, lon);
							tn.setMeta(name);
							standNodes.put(name, tn);
						}
					}
				}
			}
		} catch (Exception e) {
			System.err.println("Problem reading BGLXML file " + filename + " - maybe wrong format?");
			e.printStackTrace();
		}
		
		if (standNodes.isEmpty()) {
			System.err.println("No stands found in BGLXML file " + filename);
		} else {
			System.out.println("Found " + standNodes.size() + " stands in BGLXML file.");
		}
	}
	
	public Map<String, TaxiNode> getStandNodes() {
		return standNodes;
	}
}
