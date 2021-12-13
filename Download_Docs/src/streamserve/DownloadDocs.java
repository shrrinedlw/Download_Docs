package streamserve;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class DownloadDocs {

	private static final Logger logger = LoggerFactory.getLogger(DownloadDocs.class);
	private static final Logger errorLogger = LoggerFactory.getLogger("error");
	private static ArrayList<String> failedDocs;
	private static final XPath xPath = XPathFactory.newInstance().newXPath();
	static Document failedDocument;
	private static String strOTCSTicket = "";

	private static String strUserName = "";
	private static String strUserPassword = "";
	private static String strCSURL = "";
	private static String strFileExt = "";
	private static String strFilePath = "";
	private static String strXMLPath = "";
	private static String strXMLMovePath = "";
	private static String strXMLMoveErrorPath = "";
	private static String strXMLMoveCompletedPath = "";
	private static String strPSPath = "";

	public static void main(String[] args) {

		String strXMLFileName = "";
		InputStream inputStream;
		String timeStamp = "";

		Properties appProps = new Properties();
		try {
			timeStamp = new SimpleDateFormat("dd MMMM yyyy hh:mm:ss").format(new Date());
			logger.info("Download Document Utility started at " + timeStamp);

			String propFileName = "app.properties";
			ClassLoader loader = Thread.currentThread().getContextClassLoader();
			inputStream = loader.getResourceAsStream("app.properties");

			if (inputStream != null) {
				appProps.load(inputStream);
			} else {
				throw new FileNotFoundException("property file '" + propFileName + "' not found in the classpath");
			}

			strUserName = appProps.getProperty("username");
			strUserPassword = appProps.getProperty("password");
			strCSURL = appProps.getProperty("csurl");
			strFileExt = appProps.getProperty("fileext");
			strFilePath = appProps.getProperty("filepath");
			strXMLPath = appProps.getProperty("xmlpath");
			strXMLMovePath = appProps.getProperty("xmlmovepath");
			strXMLMoveErrorPath = appProps.getProperty("xmlerrorpath");
			strXMLMoveCompletedPath = appProps.getProperty("xmlcompletedpath");
			strPSPath = appProps.getProperty("pspath");

			File folder = new File(strXMLPath);
			File[] listOfFiles = folder.listFiles();
			for (int i = 0; i < listOfFiles.length; i++) {
				String filename = listOfFiles[i].getName();
				if (filename.endsWith(".xml") || filename.endsWith(".XML")) {
					strXMLFileName = filename;
					processXML(strXMLFileName);
				}
			}

			if (strXMLFileName.length() > 0) {
				String command = "powershell.exe & '" + strPSPath+"'";
				Process powerShellProcess = Runtime.getRuntime().exec(command);

				powerShellProcess.getOutputStream().close();
				String line;
				logger.info("Powershell output: ");
				BufferedReader stdout = new BufferedReader(new InputStreamReader(powerShellProcess.getInputStream()));
				while ((line = stdout.readLine()) != null) {
					logger.info(line);
				}
				stdout.close();
				logger.error("Powershell error: ");
				BufferedReader stderr = new BufferedReader(new InputStreamReader(powerShellProcess.getErrorStream()));
				while ((line = stderr.readLine()) != null) {
					logger.error(line);
				}
				stderr.close();
			}

			timeStamp = new SimpleDateFormat("dd MMMM yyyy hh:mm:ss").format(new Date());
			logger.info("Download Document Utility completed at " + timeStamp);

		} catch (FileNotFoundException e) {
			logger.error("Error in retrieving file", e);
		} catch (IOException e) {
			logger.error("IO Error", e);
		}

	}

	public static void processXML(String strXMLFileName) {
		Document document = null;
		failedDocs = new ArrayList<String>();

		try {

			File xmlFile = new File(strXMLPath + "\\" + strXMLFileName);
			String xml = new String(Files.readAllBytes(xmlFile.toPath()));

			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = factory.newDocumentBuilder();
			document = builder.parse(new InputSource(new StringReader(xml)));
			document.getDocumentElement().normalize();

			NodeList data_id_nodes = document.getElementsByTagName("DATAID");

			if (data_id_nodes.getLength() > 0) {
				strOTCSTicket = GetOTCSTicketForDocument(strUserName, strUserPassword, strCSURL);
				logger.info("Obtained OTCS Ticket");

				for (int i = 0; i < data_id_nodes.getLength(); i++) {
					String strDocId = data_id_nodes.item(i).getTextContent();
					logger.info("Downloading Document for : " + strDocId);
					getDocument(strOTCSTicket, strFilePath, strDocId, strFileExt, strCSURL);
				}
			} else {
				logger.info("No DataIds present for download");
			}

			// create failed xml for missing documents
			if (failedDocs.size() > 0) {
				failedDocument = builder.newDocument();
				Element root = (Element) failedDocument.createElement("asx:abap");
				((Element) root).setAttribute("xmlns:asx", "http://www.sap.com/abapxml");
				((Element) root).setAttribute("template", "040_AR_HandtekeningRapport");
				((Element) root).setAttribute("version", "1.0");
				Element asx = (Element) failedDocument.createElement("asx:values");
				Element jd = (Element) failedDocument.createElement("JOBDATA");
				asx.appendChild(jd);
				root.appendChild(asx);
				failedDocument.appendChild(root);
			}

			// update xml to add root tags and remove missing documents
			Document finalDocument = updateXML(document);
			Transformer transformer = TransformerFactory.newInstance().newTransformer();
			transformer.transform(new DOMSource(finalDocument), new StreamResult(xmlFile));
			FileUtils.moveFileToDirectory(FileUtils.getFile(xmlFile), FileUtils.getFile(strXMLMovePath), true);

			SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
			Date date = new Date();
			String strCurrentDate = formatter.format(date);
			FileUtils.copyFileToDirectory(FileUtils.getFile(strXMLMovePath + "//" + strXMLFileName),
					FileUtils.getFile(strXMLMoveCompletedPath + "\\" + strCurrentDate));
			logger.info("Updated XML " + xmlFile.getName() + " moved to " + strXMLMovePath);

			if (failedDocs.size() > 0) {

				date = new Date();
				strCurrentDate = formatter.format(date);

				String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
				File xmlFailedDocFile = new File(strXMLMoveErrorPath + "\\" + timeStamp + "_FailedDocs.xml");
				transformer.transform(new DOMSource(failedDocument), new StreamResult(xmlFailedDocFile));

				FileUtils.moveFileToDirectory(FileUtils.getFile(xmlFailedDocFile),
						FileUtils.getFile(strXMLMoveErrorPath + "\\" + strCurrentDate), true);
				logger.info("Failed Docs XML with name " + xmlFailedDocFile.getName() + " created at "
						+ xmlFailedDocFile.getAbsolutePath());
			} else {
				logger.info("No failed documents for this XML");
			}		
		} catch (TransformerException | IOException | ParserConfigurationException | SAXException  e) {
			logger.error("Exception in processXML()", e);
		}
	}

	public static String GetOTCSTicketForDocument(String username, String password, String csURL) {

		String strTicket = "";

		HttpClient httpclient = HttpClients.createDefault();
		HttpPost httppost = new HttpPost(csURL + "api/v1/auth");

		// Request parameters and other properties.
		List<NameValuePair> params = new ArrayList<NameValuePair>(2);
		params.add(new BasicNameValuePair("username", username));
		params.add(new BasicNameValuePair("password", password));

		try {
			httppost.setEntity(new UrlEncodedFormEntity(params, "UTF-8"));
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}

		// Execute and get the response.
		HttpResponse response;

		try {

			response = httpclient.execute(httppost);
			HttpEntity entity = response.getEntity();

			if (entity != null) {

				InputStream instream = entity.getContent();

				// NB: does not close inputStream, you can use IOUtils.closeQuietly for that
				String theString = IOUtils.toString(instream, "UTF-8");

				// Parse it to JSON for ease of reading
				JSONParser jsonParser = new JSONParser();
				JSONObject jsonObject = (JSONObject) jsonParser.parse(theString);

				try {

					// Try to obtain the ticket
					strTicket = (String) jsonObject.get("ticket");

				} finally {

					// close the stream
					instream.close();

				}
			}
		} catch (IOException | ParseException e) {
			logger.error("Exception in getting OTCS Ticket", e);
		}

		return strTicket;

	}

	public static void getDocument(String ticket, String filepath, String nodeid, String ext, String CSurl) {

		long start = System.nanoTime();
		String dl = CSurl + "api/v1/nodes/" + nodeid + "/content";
		URL url;
		HttpURLConnection connect = null;
		int count = 0;

		try {

			url = new URL(dl);

			// Open the connection
			connect = (HttpURLConnection) url.openConnection();

			// Set the request headers
			connect.setRequestProperty("User-Agent", "Mozilla/5.0");
			connect.setRequestProperty("OTCSticket", ticket);
			connect.setRequestProperty("action", "download");

			String strCompletePath = filepath + "\\" + nodeid + ".pdf";

			// Check whether connection was OK

			if (connect.getResponseCode() == HttpURLConnection.HTTP_OK) {

				try (BufferedInputStream in = new BufferedInputStream(connect.getInputStream());
						FileOutputStream fileOutputStream = new FileOutputStream(strCompletePath)) {

					byte dataBuffer[] = new byte[1024];
					int bytesRead;
					while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
						fileOutputStream.write(dataBuffer, 0, bytesRead);
					}
				} catch (IOException e) {
					e.printStackTrace();
				}

				logger.info("File saved to : " + strCompletePath);
				long end = System.nanoTime();
				long elapsedTime = end - start;
				long convert = TimeUnit.MILLISECONDS.convert(elapsedTime, TimeUnit.NANOSECONDS);
				logger.info("Document " + nodeid + " downloaded in " + convert + " milliseconds");

			} else if(connect.getResponseCode() == HttpURLConnection.HTTP_UNAUTHORIZED)
			{
				connect.disconnect();
				logger.info("Download failed with 401, obtaining a new OTCS ticket and retrying document download");
				strOTCSTicket = GetOTCSTicketForDocument(strUserName, strUserPassword, strCSURL);
				
				// Open the connection
				connect = (HttpURLConnection) url.openConnection();

				// Set the request headers
				connect.setRequestProperty("User-Agent", "Mozilla/5.0");
				connect.setRequestProperty("OTCSticket", strOTCSTicket);
				connect.setRequestProperty("action", "download");
								
				if (connect.getResponseCode() == HttpURLConnection.HTTP_OK) {

					try (BufferedInputStream in = new BufferedInputStream(connect.getInputStream());
							FileOutputStream fileOutputStream = new FileOutputStream(strCompletePath)) {

						byte dataBuffer[] = new byte[1024];
						int bytesRead;
						while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
							fileOutputStream.write(dataBuffer, 0, bytesRead);
						}
					} catch (IOException e) {
						e.printStackTrace();
					}

					logger.info("File saved to : " + strCompletePath);
					long end = System.nanoTime();
					long elapsedTime = end - start;
					long convert = TimeUnit.MILLISECONDS.convert(elapsedTime, TimeUnit.NANOSECONDS);
					logger.info("Document " + nodeid + " downloaded in " + convert + " milliseconds");

				} else {
					failedDocs.add(nodeid);
					logger.error("Error in document download for id " + nodeid + " with status code: "
							+ connect.getResponseCode());
					errorLogger.error("Error in document download for id " + nodeid + " with status code: "
							+ connect.getResponseCode());
				}
			}
			else {
				while (count < 3) {

					logger.info("Retrying download for document " + nodeid + " downloaded; attempt: "
							+ String.valueOf(count + 1));
					if (connect.getResponseCode() == HttpURLConnection.HTTP_OK) {

						try (BufferedInputStream in = new BufferedInputStream(connect.getInputStream());
								FileOutputStream fileOutputStream = new FileOutputStream(strCompletePath)) {

							byte dataBuffer[] = new byte[1024];
							int bytesRead;
							while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
								fileOutputStream.write(dataBuffer, 0, bytesRead);
							}
						} catch (IOException e) {
							e.printStackTrace();
						}

						logger.info("File saved to : " + strCompletePath);
						long end = System.nanoTime();
						long elapsedTime = end - start;
						long convert = TimeUnit.MILLISECONDS.convert(elapsedTime, TimeUnit.NANOSECONDS);
						logger.info("Document " + nodeid + " downloaded in " + convert + " milliseconds");
						break;

					} else {
						count++;
					}
				}

				failedDocs.add(nodeid);
				logger.error("Error in document download for id " + nodeid + " with status code: "
						+ connect.getResponseCode());
				errorLogger.error("Error in document download for id " + nodeid + " with status code: "
						+ connect.getResponseCode());
			}

		} catch (Exception e) {
			logger.error("Exception in downloading document", e);
		} finally {
			connect.disconnect();
		}
	}

	public static Document updateXML(Document inputXML) {
		try {

			for (String failedDoc : failedDocs) {
				String xPathExpression = "//DOCUMENT[./DOC_OUTPUT_DATA/BIJLAGEN_PARAM_ECM/ZSCS_SIG_BIJLAGEN_PARAM_ECM/DATAID/text()='"
						+ failedDoc + "']";
				NodeList nodes = (NodeList) xPath.evaluate(xPathExpression, inputXML, XPathConstants.NODESET);
				for (int i = nodes.getLength() - 1; i >= 0; i--) {

					NodeList nodesFailedDocs = (NodeList) xPath.evaluate(xPathExpression, failedDocument,
							XPathConstants.NODESET);
					if (nodesFailedDocs.getLength() == 0) {
						Element jd = (Element) failedDocument.getElementsByTagName("JOBDATA").item(0);
						jd.appendChild(failedDocument.adoptNode(nodes.item(i).cloneNode(true)));
					}
					nodes.item(i).getParentNode().removeChild(nodes.item(i));
				}
			}

			Element newRoot = inputXML.createElement("PRINTJOBDATA");
			newRoot.appendChild(inputXML.getFirstChild());
			inputXML.appendChild(newRoot);

		} catch (Exception e) {
			logger.error("Exception in updateXML()", e);
		} finally {

		}

		return inputXML;

	}

}
