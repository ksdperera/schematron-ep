package net.sourceforge.schematronep.builder;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.Configuration;
import net.sf.saxon.TransformerFactoryImpl;
import net.sourceforge.schematronep.CustomURIResolver;
import net.sourceforge.schematronep.Log;
import net.sourceforge.schematronep.SchematronPlugin;
import net.sourceforge.schematronep.TemplatesManager;
import net.sourceforge.schematronep.Utils;
import net.sourceforge.schematronep.preferences.PreferenceKeys;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ProcessingInstruction;
import org.xml.sax.SAXException;

import com.sun.corba.se.internal.javax.rmi.CORBA.Util;

public class SchematronBuilder extends IncrementalProjectBuilder
{
	/** The factory that will create the transformers to apply the XSLs */
	private TransformerFactory txFac = null;

	/** The URI resolver to allow custom file system lookups */
	private CustomURIResolver resolver = null;

	public static final String EXT_XML = "xml";

	public static final String EXT_SCH = "sch";

	public static final String PATH_SKELETON_1_5 = "net/sourceforge/schematronep/xsl/skeleton1-5.xsl";

	public static final String BUILDER_ID = "net.sourceforge.schematronep.schematronBuilder";

	private static final String MARKER_TYPE = "net.sourceforge.schematronep.xmlProblem";

	public static final String VALIDATION_DELIMITER = "|";

	private Map xmlFileMap = new HashMap();

	private TemplatesManager templatesMgr = null;

	private URL schematronXslURL = null;

	/**
	 * Initialise the transformationFactory with a config so that we can turn
	 * line numbering on which we need to allow the markers to be placed at the
	 * correct location in the UI
	 */
	public SchematronBuilder()
	{
		Configuration config = new Configuration();
		config.setLineNumbering(true);
		// Suppress the "Running an XSLT 1.0 stylesheet with an XSLT 2.0 processor" messages
		config.setVersionWarning(false);
		txFac = new TransformerFactoryImpl(config);
		resolver = new CustomURIResolver(txFac.getURIResolver(), null);
		txFac.setURIResolver(resolver);

		templatesMgr = new TemplatesManager(txFac);

		ClassLoader loader = SchematronPlugin.getDefault().getDescriptor().getPluginClassLoader();

		schematronXslURL = loader.getResource(PATH_SKELETON_1_5);
	}

	class SampleDeltaVisitor implements IResourceDeltaVisitor
	{
		/*
		 * (non-Javadoc)
		 * 
		 * @see org.eclipse.core.resources.IResourceDeltaVisitor#visit(org.eclipse.core.resources.IResourceDelta)
		 */
		public boolean visit(IResourceDelta delta) throws CoreException
		{
			IResource resource = delta.getResource();
			switch (delta.getKind())
			{
			case IResourceDelta.ADDED:
				// handle added resource
				buildXML(resource);
				break;
			case IResourceDelta.REMOVED:
				// handle removed resource
				break;
			case IResourceDelta.CHANGED:
				// handle changed resource
				buildXML(resource);
				break;
			}
			// return true to continue visiting children.
			return true;
		}
	}

	class SampleResourceVisitor implements IResourceVisitor
	{
		public boolean visit(IResource resource)
		{
			buildXML(resource);
			// return true to continue visiting children.
			return true;
		}
	}

	private void addMarker(IFile file, String message, int lineNumber, int severity)
	{
		try
		{
			IMarker marker = file.createMarker(MARKER_TYPE);
			marker.setAttribute(IMarker.MESSAGE, message);
			marker.setAttribute(IMarker.SEVERITY, severity);
			if (lineNumber == -1)
			{
				lineNumber = 1;
			}
			marker.setAttribute(IMarker.LINE_NUMBER, lineNumber);
		}
		catch (CoreException e)
		{
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.core.internal.events.InternalBuilder#build(int,
	 *      java.util.Map, org.eclipse.core.runtime.IProgressMonitor)
	 */
	protected IProject[] build(int kind, Map args, IProgressMonitor monitor) throws CoreException
	{
		if (kind == FULL_BUILD)
		{
			fullBuild(monitor);
		}
		else
		{
			IResourceDelta delta = getDelta(getProject());
			if (delta == null)
			{
				fullBuild(monitor);
			}
			else
			{
				incrementalBuild(delta, monitor);
			}
		}
		return null;
	}

	private Document createDocument(IFile file) throws ParserConfigurationException, IOException, CoreException,
					SAXException
	{
		DocumentBuilderFactory fac = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder = fac.newDocumentBuilder();

		return builder.parse(file.getContents());
	}

	private byte[] transformToByteArray(IFile xml, Templates t) throws CoreException, TransformerException,
					FileNotFoundException
	{
		Transformer tx = t.newTransformer();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		tx.transform(new StreamSource(xml.getContents()), new StreamResult(baos));

		return baos.toByteArray();
	}

	private Set extractSchemasFromPIs(IFile file) throws Exception
	{
		Set schemaList = new HashSet();

		Document doc = createDocument(file);
		NodeList list = doc.getChildNodes();
		for (int i = 0; i < list.getLength(); i++)
		{
			Node node = list.item(i);
			if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE)
			{
				ProcessingInstruction pi = (ProcessingInstruction) node;

				// Pull out the href value
				String d = pi.getData();
				String sch = d.substring(d.indexOf("=\"") + 2, d.lastIndexOf("\""));
				Log.debug("Schema = " + sch);

				IFile schemaFile = Utils.convert(resolver.resolve(sch));

				if (schemaFile.exists() == false)
				{
					this.addMarker(file, "Schema " + schemaFile.getLocation() + " does not exist", 1,
									IMarker.SEVERITY_ERROR);
				}
				else
				{
					schemaList.add(schemaFile.getLocation().toString());
				}
			}
		}

		return schemaList;
	}

	private void processXML(File file)
	{
		processXML(Utils.convert(file));
	}

	private void processXML(IFile file)
	{
		deleteMarkers(file);
		Log.debug("Found file: " + file.getName() + "; isDerived=" + file.isDerived());

		// Set the base for resolution
		resolver.setBase(file);
		try
		{

			Set assocSchematronFiles = extractSchemasFromPIs(file);

			Iterator it = assocSchematronFiles.iterator();

			xmlFileMap.remove(file.getLocation());

			xmlFileMap.put(file.getLocation(), assocSchematronFiles);

			while (it.hasNext())
			{
				String schFileName = (String) it.next();

				File schemaFile = new File(schFileName);

				InputStream is = null;

				try
				{
					Templates validationTemplates = templatesMgr.getTemplates(schemaFile);

					if (validationTemplates == null)
					{
						Log.debug("Templates don't exist for schema " + schFileName + ". Creating...");
						// Get the schematron xsl templates
						Templates schematronTemplates = templatesMgr.createTemplates(schematronXslURL);

						// Now take the schematronTemplates and apply the schema to create another
						// templates object for validation
						validationTemplates = templatesMgr.createTemplates(schematronTemplates, schemaFile);						
					}

					byte[] output = transformToByteArray(file, validationTemplates);

					processErrors(file, output, schemaFile);
				}
				finally
				{
					if (is != null)
					{
						is.close();
					}
				}
			}
		}
		catch (Exception e)
		{
			this.addMarker(file, e.toString(), 1, IMarker.SEVERITY_ERROR);
			Log.error("Failed to build " + file, e);
		}
	}

	private void processSCH(IFile file)
	{
		deleteMarkers(file);
		Log.debug("Found file: " + file.getName() + "; isDerived=" + file.isDerived());

		// Set the base for resolution
		resolver.setBase(file);

		String location = file.getLocation().toString();

		// Remove this sch from the cache so that it will be recompiled when next used
		templatesMgr.removeTemplates(file);

		// Get the location of this file and hunt through the
		Set keys = xmlFileMap.keySet();
		Iterator it = keys.iterator();
		while (it.hasNext())
		{
			IPath key = (IPath) it.next();

			Set schSet = (Set) xmlFileMap.get(key);
			if (schSet.contains(location))
			{
				// Call processXML with the correct path
				Log.debug("Need to verify " + key);
				this.processXML(key.toFile());
			}
		}
	}

	void buildXML(IResource resource)
	{
		// Don't want to check files in the compiled directory, e.g. bin
		if (resource instanceof IFile && resource.isDerived() == false)
		{
			IFile file = (IFile) resource;

			if (file.getFileExtension().equals(EXT_XML))
			{
				processXML(file);
			}
			else if (file.getFileExtension().equals(EXT_SCH))
			{
				// The schema is being recompiled. Need to make sure that
				// the XML that refers to it is recompiled too
				// How do we set file dependencies?
				processSCH(file);
			}
		}
	}

	private void processErrors(IFile file, byte[] bs, File schemaFile) throws IOException
	{
		BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bs)));
		String line = reader.readLine();

		while (line != null)
		{
			Log.debug("processErrors: Read line = " + line);
			StringTokenizer st = new StringTokenizer(line, VALIDATION_DELIMITER);

			while (st.hasMoreTokens())
			{
				String token = st.nextToken();
				int lineNumber = Integer.parseInt(token.substring(0, token.indexOf(":")));
				this.addMarker(file, token + " from schema " + schemaFile.getAbsolutePath(), lineNumber,
								IMarker.SEVERITY_ERROR);
			}

			line = reader.readLine();
		}
		reader.close();
	}

	class SchematronValidationErrorListener implements ErrorListener
	{
		IFile file = null;

		SchematronValidationErrorListener(IFile aFile)
		{
			file = aFile;
		}

		public void error(TransformerException arg0) throws TransformerException
		{
			addMarker(file, arg0.getMessageAndLocation(), arg0.getLocator().getLineNumber(), IMarker.SEVERITY_ERROR);
		}

		public void fatalError(TransformerException arg0) throws TransformerException
		{
			addMarker(file, arg0.getMessageAndLocation(), arg0.getLocator().getLineNumber(), IMarker.SEVERITY_ERROR);
		}

		public void warning(TransformerException arg0) throws TransformerException
		{
			addMarker(file, arg0.getMessageAndLocation(), arg0.getLocator().getLineNumber(), IMarker.SEVERITY_WARNING);
		}

	}

	private void deleteMarkers(IFile file)
	{
		try
		{
			file.deleteMarkers(MARKER_TYPE, false, IResource.DEPTH_ZERO);
		}
		catch (CoreException ce)
		{
		}
	}

	protected void fullBuild(final IProgressMonitor monitor) throws CoreException
	{
		try
		{
			getProject().accept(new SampleResourceVisitor());
		}
		catch (CoreException e)
		{
		}
	}

	protected void incrementalBuild(IResourceDelta delta, IProgressMonitor monitor) throws CoreException
	{
		// the visitor does the work.
		delta.accept(new SampleDeltaVisitor());
	}

}
