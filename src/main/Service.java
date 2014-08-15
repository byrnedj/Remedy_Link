package main;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringWriter;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.jsoup.Jsoup;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import sun.misc.BASE64Encoder;



//TODO: Rewrite according to remedy specs.
/**
 * This is it, the brains of the operation. In the Service class we do the following on a cycle:
 * 
 * 1. Authorize our HPQC client using LWSSO cookie
 * 2. Get the list of issues (defects) from HPQC via getHPQC and store them in a Map by the HPQC ID
 * 3. Get the list of issues from JIRA. JIRA uses basic auth (64bit encoded don't ever send via internet) and store them
 * 4. Resolve the deltas between the lists.
 * 		- This gets the corresponding issues from HPQC and JIRA by the HPQC ID and then checks to see who was last modified
 * 		- If there is no issue in JIRA, one will be created for you
 * 		- IMPORTANT: THIS SERVICE WAS BUILT ON THE PREMISE THAT YOU NEVER NEED TO CREATE AN ISSUE OF 'HPQC Defect' TYPE IN JIRA
 *        THE WORKFLOW SHOULD BE: 
 *        Tester creates defect in HPQC -> issue replicated in JIRA for developer -> developer makes changes -> issue synced back to HPQC
 * 			
 * 5. Post back changes of the lists to the corresponding servers.
 * 		-Only posts if wasChanged or wasAdded (for JIRA items only)
 * 
 * 6. Refresh wasAdded and wasChanged fields and begin new cycle.
 * 
 * If you would like to add a project to this service do a "put" on the lProjects map in the main method. 
 * -The key is the JIRA project and the value is the REMEDY project.
 * 
 * Important member fields of this class and their uses:
 * - JIRA_URI, the base URI (or URL for all matters), right now it is being hosted on our baby usnxv224, if you change JIRA servers, this needs changing.
 * 
 * 
 * A note about style,
 * -You may or may not like the Egyptian style brackets, spaces between parameter calls, or comments throughout. Deal with it because
 * that was how I learned to code.
 * -I try to keep consistent in variable naming convention: m prefix = member variable, l prefix = local variable, 
 *                                                          a prefix = argument variable
 * -Yes, I know I don't always do this, especially for loop variables, but it helps to follow something. 
 * 
 * Dependencies,
 * -This project depends on json_simple-1.1, its a free JSON Parser, it might still be on usnxv224.
 * -This project depends on jsoup-1.7.3, its for parsing html as that is how descriptive fields come in from HPQC.
 * 
 * @author Daniel Byrne - byrnedj12@gmail.com if he is not in the corporate directory.
 *
 */
public class Service 
{
	private static String JIRA_URI = "http://usnxv224:8080";
	private static String USER;
	private static String PWD;

	/**
	 * @param args
	 */
	public static void main( String[] args ) throws ParseException
	{
		
		Options options = new Options();
		
		options.addOption( "user", true, "REMEDY ID" );
		options.addOption( "password", true, "REMEDY PWD" );

		CommandLineParser cliParser = new GnuParser();			
		
		//Parse the command line
		CommandLine cmd = cliParser.parse( options, args );
		
			
		USER = cmd.getOptionValue( "user" );
		PWD = cmd.getOptionValue( "password" );

		
		
		//Subsequent cycles every 2 minutes
		ScheduledExecutorService scheduler =
			    Executors.newSingleThreadScheduledExecutor();

		scheduler.scheduleAtFixedRate( new Runnable() 
		{ 
			public void run() 
			{ 
				cycle(); 
			} 

		}, 0, 1, TimeUnit.MINUTES );


/*		//KEY = JIRA project name
		//VALUE = REMEDY project name
		Map<String, String> lProjects = new HashMap<String, String>();
		lProjects.put( "AD", "JIRA_TEST" );

		for ( Entry<String, String> project : lProjects.entrySet() )
		{

			//Get our project names from the map.
			String lJIRAProjectName = project.getKey();
			String lREMEDYProjectName = project.getValue();

			//REMEDY Init
			String LWSSO_COOKIE = authREMEDY();
			Map<String,Issue> lREMEDY = getREMEDY( LWSSO_COOKIE, lREMEDYProjectName );	
			Map<String,Issue> preProcessREMEDY = getREMEDY( LWSSO_COOKIE, lREMEDYProjectName ); //temp fix: TODO: figure better way of checking for changes

			//JIRA Init
			Map<String,Issue> lJIRA = getJIRA( lJIRAProjectName );
			Map<String,Issue> preProcessJIRA = getJIRA( lJIRAProjectName );

			//Resolve deltas (not the airline)
			betterProcessChanges( lREMEDY, lJIRA );
			
			if ( !preProcessREMEDY.equals( lREMEDY ) )
			{
				postREMEDY( lREMEDY, LWSSO_COOKIE, lREMEDYProjectName );
			}
			if ( !preProcessJIRA.equals( lJIRA ) )
			{	
				postJIRA ( lJIRA, lJIRAProjectName );
			}

		}*/
	}


	/**
	 * This is the main cycle.
	 */
	public static void cycle()
	{

		//KEY = JIRA project name
		//VALUE = REMEDY project name
		Map<String, String> lProjects = new HashMap<String, String>();
		//Projects go here:
		lProjects.put( "AD", "JIRA_TEST" );
		
		//Loop through each project
		for ( Entry<String, String> project : lProjects.entrySet() )
		{
			
			//Get our project names from the map.
			String lJIRAProjectName = project.getKey();
			String lREMEDYProjectName = project.getValue();
			
			System.out.println( "Begin cycle for JIRA project: " + lJIRAProjectName );

			//REMEDY Init
			Map<String,Issue> lREMEDY = getREMEDY( LWSSO_COOKIE, lREMEDYProjectName );	

			//JIRA Init
			Map<String,Issue> lJIRA = getJIRA( lJIRAProjectName );

			//Resolve deltas (not the airline)
			betterProcessChanges( lREMEDY, lJIRA );
			
			//post up
			postREMEDY( lREMEDY, LWSSO_COOKIE, lREMEDYProjectName );
			postJIRA ( lJIRA, lJIRAProjectName );
			
			System.out.println( "Ending cycle for JIRA project: " +  lJIRAProjectName );

		}
		System.out.println( "End cycle..." );
	}

	/**
	 * The change logic is done here, this is rev2 so it should be much more robust.
	 * @param aREMEDY REMEDY current issue list
	 * @param aJIRA JIRA current issue list
	 */
	public static void betterProcessChanges( Map<String,Issue> aREMEDY, Map<String,Issue> aJIRA )
	{
		//Date format for comparison
		SimpleDateFormat dateFormat = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss" );

		//Start with an entry from REMEDY (guaranteed)
		for ( Entry<String, Issue> lREMEDYEntry : aREMEDY.entrySet() )
		{
			Issue lREMEDYIssue = lREMEDYEntry.getValue();
			String lREMEDYKey = lREMEDYEntry.getKey();
			//If JIRA already has this issue then we should check if they are in sync.
			if ( aJIRA.containsKey( lREMEDYKey ) )
			{
				
				Issue lJIRAIssue = aJIRA.get( lREMEDYKey );
				//if REMEDY issue was last modified AFTER the corresponding issue in JIRA was modified
				//then the JIRA issue is out of date
				if ( lREMEDYIssue.getLastModified().after( lJIRAIssue.getLastModified() ) )
				{
					System.out.println( "found old jira issue: " + lJIRAIssue.getJIRAKey() + ", REMEDY ID: " + lJIRAIssue.getID() 
							+ ", the last change in REMEDY was at: "+ dateFormat.format( lREMEDYIssue.getLastModified() ) + " and the last change in JIRA was " +  lJIRAIssue.getLastModified() );

					//set the new values
					lJIRAIssue.setSyncedFields( lREMEDYIssue.getSyncedFields() );

				}
				//if the REMEDY issue was last modified BEFORE the corresponding issue in JIRA was modified
				//the the REMEDY issue is out of date
				else if ( lREMEDYIssue.getLastModified().before( lJIRAIssue.getLastModified() ) )
				{
					System.out.println( "found old REMEDY issue: " + lREMEDYIssue.getID()
							+ ", the last change in JIRA was at: "+ dateFormat.format( lJIRAIssue.getLastModified() ) );
					//set the new values
					lREMEDYIssue.setSyncedFields( lJIRAIssue.getSyncedFields() );
				}
			}
			//if there is no matching key then we have to create an issue in JIRA aka add it to the JIRA map
			else
			{
				System.out.println("added: " +  lREMEDYIssue.getID() + " to the list of JIRA" );
				lREMEDYIssue.setAdded( true );
				aJIRA.put( lREMEDYKey, lREMEDYIssue );

			}
		}

	}



	/**
	 * This gets the list of defects of the current REMEDY project.
	 * @param LWSSO_COOKIE
	 * @return a map of the Issues , the key is the defect ID number
	 */
	public static Map<String, Issue> getREMEDY( String LWSSO_COOKIE, String aProjectName )
	{
		return null;
	}

	/**
	 * Gets the list of issues based of a project name
	 * @param aProjectName the project that issues of HPQC Defect type will be retrieved
	 * @return a map of Issues, where key is equal to the HPQC Defect ID
	 * 
	 */
	public static Map<String, Issue> getJIRA( String aProjectName )
	{

		Map<String, Issue> lIssueList = new HashMap<String, Issue>();

		try
		{
			URL authURL = new URL( JIRA_URI + "/rest/api/2/search?jql=project=" + aProjectName + "&maxResults=10000" );
			HttpURLConnection conn = (HttpURLConnection) authURL.openConnection();
			byte[] credBytes = ( "sync" + ":" + "sync").getBytes();
			String credEncodedString = "Basic " + new BASE64Encoder().encode( credBytes );
			conn.setRequestProperty( "Authorization", credEncodedString );
			conn.setRequestMethod( "GET" );
			conn.setRequestProperty( "Accept", "application/json" );

			//Reader rabbit because even developers need to have fun.
			Reader rabbit = new InputStreamReader( conn.getInputStream() );
			Object issues = JSONValue.parse( rabbit );
			lIssueList = convertFromJSON( issues );
		}
		catch( Exception e )
		{
			e.printStackTrace();
		}
		return lIssueList;

	}

	/**
	 * Converts a JSON list of issues (from JIRA) to a map of Issues we can work with
	 * @param aIssues JSON representation of issues
	 * @return a map of Issues, where key is equal to the HPQC Defect ID 
	 */
	public static Map<String, Issue> convertFromJSON( Object aIssues )
	{
		//We have to get the array of issues out
		JSONArray lListFromJIRA = (JSONArray) ((Map)aIssues).get( "issues" );
		Map<String, Issue> lIssueList = new HashMap<String, Issue>();

		//Process the array of issues
		for ( int i = 0; i < lListFromJIRA.size(); i++ )
		{
			JSONObject lCurrentIssue = (JSONObject) lListFromJIRA.get( i );
			JSONObject lFields = (JSONObject) lCurrentIssue.get( "fields" );

			//We only sync issue types of HPQC Defect
			if ( (((Map) lFields.get( "issuetype" )).get( "name" ).equals( "HPQC Defect" ) ) )
			{

				Map<String,String> lNewIssueFieldMap = new HashMap<String,String>();
				//We use Issue.JIRA_NAME_MAP to map the names of fields in JIRA to HPQC defect names.

				lNewIssueFieldMap.put( Issue.JIRA_NAME_MAP.get( "summary" ), (String) lFields.get( "summary" ) );

				lNewIssueFieldMap.put( Issue.JIRA_NAME_MAP.get( "description" ), (String) lFields.get( "description" ) );

				//HPQC-ID field in JIRA
				lNewIssueFieldMap.put( Issue.JIRA_NAME_MAP.get( "customfield_10609" ), (String) lFields.get( "customfield_10609" ) );

				//HPQC Status field in JIRA
				lNewIssueFieldMap.put( Issue.JIRA_NAME_MAP.get( "customfield_10612" ), (String) lFields.get( "customfield_10612" ) );

				lNewIssueFieldMap.put( Issue.JIRA_NAME_MAP.get( "updated" ), (String) lFields.get( "updated" ) );

				//Note the additional parsing for reporter and assignee
				lNewIssueFieldMap.put( Issue.JIRA_NAME_MAP.get( "reporter" ), 
						(String) ((JSONObject) lFields.get( "reporter" )).get( "name" ) );

				lNewIssueFieldMap.put( Issue.JIRA_NAME_MAP.get( "assignee" ), 
						(String) ((JSONObject) lFields.get( "assignee" )).get( "name" ) );

				//JIRA formats dates differently so we pass this on to let the constructor for the issue know what's up.
				lNewIssueFieldMap.put( "isJIRA", "true" );

				//Create the new issue and set the key to the HPQC - ID
				Issue lNewIssue = new Issue( lNewIssueFieldMap );
				lNewIssue.setJIRAKey( lCurrentIssue.get( "key" ).toString() );
				lIssueList.put( (String) lFields.get( "customfield_10609" ) , lNewIssue );
			}

		}
		return lIssueList;
	}

	/**
	 * Post up the new changes. 
	 * @param aREMEDY the issues to post
	 * @param LWSSO_COOKIE
	 */
	public static void postREMEDY( Map<String, Issue> aHPQC, String LWSSO_COOKIE, String aProjectName )
	{

	}

	/**
	 * Post up to JIRA almighty
	 * @param aJIRA the map of issues to post
	 * @param aProjectName the name of the project
	 */
	public static void postJIRA( Map<String, Issue> aJIRA, String aProjectName )
	{
		for ( Entry<String, Issue> lEntry : aJIRA.entrySet() )
		{
			Issue lIssue = lEntry.getValue();
			//if the entry was added we have to do a POST per the JIRA REST spec
			if ( lIssue.wasAdded() )
			{
				JSONObject lToAdd = convertToJSON( lIssue, aProjectName );
				String issueCreateURL = JIRA_URI + "/rest/api/2/issue/";
				try
				{
					URL issueCreate = new URL( issueCreateURL );
					HttpURLConnection conn = (HttpURLConnection) issueCreate.openConnection();
					byte[] credBytes = ( "sync" + ":" + "sync").getBytes();
					String credEncodedString = "Basic " + new BASE64Encoder().encode( credBytes );
					conn.setDoOutput( true );
					conn.setRequestProperty( "Authorization", credEncodedString );
					conn.setRequestMethod( "POST" );
					conn.setRequestProperty( "Content-Type", "application/json" );
					OutputStreamWriter out = new OutputStreamWriter( conn.getOutputStream() );
					out.write( JSONObject.toJSONString( lToAdd ) );
					out.close();

					//Always nice to know it made it.
					System.out.println( "Create of REMEDY ID: " + lIssue.getID() + " was " + conn.getResponseMessage() );
					//System.out.println(JSONObject.toJSONString( lToAdd ));
					//Read response using reader rabbit
					Reader rabbit = new InputStreamReader( conn.getInputStream() );
					Map response = (Map)(JSONValue.parse( rabbit ));

					//Make sure to set the JIRA key once we made it.
					String lJIRAKey = response.get( "key" ).toString();
					lIssue.setJIRAKey( lJIRAKey );
					aJIRA.put( lEntry.getKey(), lIssue );
				}
				catch (Exception e)
				{
					e.printStackTrace();
				}

			}
			//If the issue was simply updated we need to do a put per JIRA REST spec
			else if ( lIssue.hasChanged() )
			{
				JSONObject lToAdd = convertToJSON( lIssue, aProjectName );
				//URI based off of issue key

				String issueChangedURL = JIRA_URI + "/rest/api/2/issue/" + lIssue.getJIRAKey();
				try
				{
					URL issueChanged = new URL( issueChangedURL );
					HttpURLConnection conn = (HttpURLConnection) issueChanged.openConnection();
					byte[] credBytes = ( "sync" + ":" + "sync").getBytes();
					String credEncodedString = "Basic " + new BASE64Encoder().encode( credBytes );
					conn.setDoOutput( true );
					conn.setRequestProperty( "Authorization", credEncodedString );
					conn.setRequestMethod( "PUT" );
					conn.setRequestProperty( "Content-Type", "application/json" );

					OutputStreamWriter out = new OutputStreamWriter( conn.getOutputStream() );
					out.write( JSONObject.toJSONString( lToAdd ) );
					out.close();

					System.out.println( "Update of REMEMDY ID: " + lIssue.getID() + " was " + conn.getResponseMessage() );

				}
				catch (Exception e)
				{
					e.printStackTrace();
				}

			}
		}
	}

	/**
	 * Converts an issue to exporting in the XML format
	 * TODO: look at SAX parsing
	 * @param aIssue to convert to XML
	 * @return XML DOM representation
	 */
	//TODO: UPDATE TO REMEDY SPECS
	public static Document convertToXML( Issue aIssue )
	{

		Document defect = null;
		try
		{

			//This XML file is built according to the spec in the HPQC ALM REST API 
			DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

			defect = docBuilder.newDocument();

			//Start with <Entity Type="defect">
			Element root = defect.createElement( "Entity" );
			root.setAttribute( "Type", "defect" );
			defect.appendChild( root );

			//Setup fields
			Element fields = defect.createElement( "Fields" );
			root.appendChild( fields );

			//ID
			Element id = defect.createElement( "Field" );
			id.setAttribute( "Name", "id" );
			id.appendChild( defect.createElement( "Value" ) ).setTextContent( aIssue.getID() );
			fields.appendChild( id );

			//synced fields
			Map<String, String> syncedFields = aIssue.getSyncedFields();
			for ( Entry<String, String> field : syncedFields.entrySet() )
			{
				Element lNewField = defect.createElement( "Field" );
				lNewField.setAttribute( "Name", field.getKey() );
				lNewField.appendChild( defect.createElement( "Value" ) ).setTextContent( field.getValue() );
				fields.appendChild( lNewField );
			}

		}
		catch( Exception e )
		{
			e.printStackTrace();
		}
		return defect;
	}

	/**
	 * Converts a document that we got from HPQC to a map of issues
	 * TODO: check out SAX parsing 
	 * @param aDoc from HPQC get method
	 * @return the wonderful map of issues.
	 */
	public static Map<String,Issue> convertFromXML( Document aDoc )
	{
		Map<String,Issue> lIssueList = new HashMap<String,Issue>();
		try
		{
			aDoc.getDocumentElement().normalize();
			NodeList entities = aDoc.getDocumentElement().getChildNodes();

			for ( int i = 0; i < entities.getLength(); i++ )
			{
				Node currentDefect = entities.item( i );
				currentDefect.normalize();
				Node fields = currentDefect.getFirstChild();

				Map<String,String> fieldMap = new HashMap<String,String>();
				String key = "";

				//Populate our field map in order to create the defect
				Node currentField = fields.getFirstChild();
				//Loop through the fields until we have parsed them all
				while( currentField != null )
				{
					String fieldName = currentField.getAttributes().item( 0 ).getNodeValue();
					//Make sure there is a value to parse and it is part of our HPQC field name map
					if (currentField.hasChildNodes() && Issue.REMEDY_NAME_MAP.containsKey( fieldName ) )
					{
						//Value will be blank string if it doesn't get set
						String value = "";
						if ( currentField.getFirstChild().hasChildNodes() )
						{
							//We have to parse the html text from these fields.
							if ( fieldName.equals( "description" ) || fieldName.equals( "dev-comments" ) )
							{
								org.jsoup.nodes.Document html = 
										Jsoup.parse( currentField.getFirstChild().getFirstChild().getNodeValue() );
								value = html.text();
							}
							else
							{
								value = currentField.getFirstChild().getFirstChild().getNodeValue();
							}
						}
						//we have to store the id as a key for the map we are generating
						if ( fieldName.equals( "id" ) )
						{
							key = value;
						}
						fieldMap.put( fieldName, value );		
					}
					//move on
					currentField = currentField.getNextSibling();
				}
				Issue lNewIssue = new Issue( fieldMap );
				lIssueList.put( key, lNewIssue );
			}

		}
		catch (Exception e)
		{
			e.printStackTrace();
		}

		return lIssueList;
	}

	/**
	 * Converts an issue to a JSON object for posting up
	 * @param aIssue to convert to JSON
	 * @param aProjectName 
	 * @return JSON representation of the issue
	 */
	@SuppressWarnings("unchecked")
	public static JSONObject convertToJSON( Issue aIssue, String aProjectName )
	{
		JSONObject lNewIssue = new JSONObject();
		JSONObject fields = new JSONObject();

		//Key (if exists)
		if ( aIssue.getJIRAKey() != null )
		{
			lNewIssue.put( "key", aIssue.getJIRAKey() );
		}

		//Begin non synced fields

		//IssueType
		JSONObject issueType = new JSONObject();
		issueType.put( "name", "HPQC Defect" );
		fields.put( "issuetype", issueType );
		
		//Project
		JSONObject project = new JSONObject();
		project.put( "key", aProjectName );
		fields.put( "project", project );
		
		//HPQC ID
		fields.put( "customfield_10609", aIssue.getID() );

		//Synced fields
		Map<String, String> lSyncedFields = aIssue.getSyncedFields();

		for ( Entry<String, String> field : lSyncedFields.entrySet() )
		{
			String lJIRAFieldName = Issue.REMEDY_NAME_MAP.get( field.getKey() );
			//assignee special case
			if ( field.getKey().equals( Issue.JIRA_NAME_MAP.get( "assignee" ) ) )
			{
				JSONObject assignee = new JSONObject();
				assignee.put( "name", field.getValue() );
				fields.put( "assignee", assignee );
			}
			//reporter special case
			else if ( field.getKey().equals( Issue.JIRA_NAME_MAP.get( "reporter" ) ) )
			{
				JSONObject reporter = new JSONObject();
				reporter.put( "name", field.getValue() );
				fields.put( "reporter", reporter );
			}
			//all others so far
			else
			{
				fields.put( lJIRAFieldName , field.getValue() );
			}
		}

		//Set our new fields and report out
		lNewIssue.put( "fields", fields );
		return lNewIssue;
	}
}
