/********************************************************************************/
/*										*/
/*		BdocRepository.java						*/
/*										*/
/*	Bubbles Environment Documentation repository of available javadocs	*/
/*										*/
/********************************************************************************/
/*	Copyright 2009 Brown University -- Steven P. Reiss		      */
/*********************************************************************************
 *  Copyright 2011, Brown University, Providence, RI.				 *
 *										 *
 *			  All Rights Reserved					 *
 *										 *
 * This program and the accompanying materials are made available under the	 *
 * terms of the Eclipse Public License v1.0 which accompanies this distribution, *
 * and is available at								 *
 *	http://www.eclipse.org/legal/epl-v10.html				 *
 *										 *
 ********************************************************************************/


/* RCS: $Header$ */

/*********************************************************************************
 *
 * $Log$
 *
 ********************************************************************************/


package edu.brown.cs.bubbles.bdoc;

import edu.brown.cs.bubbles.bass.BassConstants.BassRepository;
import edu.brown.cs.bubbles.bass.BassName;
import edu.brown.cs.bubbles.board.*;
import edu.brown.cs.bubbles.bump.BumpClient;

import edu.brown.cs.ivy.xml.IvyXml;
import edu.brown.cs.ivy.xml.IvyXmlWriter;

import org.w3c.dom.Element;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import javax.xml.parsers.*;

import java.io.*;
import java.net.*;
import java.util.*;



class BdocRepository implements BassRepository, BdocConstants
{



/********************************************************************************/
/*										*/
/*	Private Storage 							*/
/*										*/
/********************************************************************************/

private Map<String,BdocReference> all_items;
private Collection<BdocInheritedReference> inherited_items;
private int			ready_count;
private boolean 		cache_repository;
private List<String>		bdoc_props;

private static ParserDelegator	parser_delegator = new ParserDelegator();



/********************************************************************************/
/*										*/
/*	Constructors								*/
/*										*/
/********************************************************************************/

BdocRepository()
{
   all_items = new HashMap<String,BdocReference>();
   inherited_items = new ArrayList<BdocInheritedReference>();
   cache_repository = true;

   bdoc_props = new ArrayList<String>();
   List<String> plist = new ArrayList<String>();
   plist.add("Bdoc.javadoc.");
   BumpClient bc = BumpClient.getBump();
   Element e = bc.getAllProjects();
   for (Element pe : IvyXml.children(e,"PROJECT")) {
      String nm = IvyXml.getAttrString(pe,"NAME");
      if (nm != null) {
	 nm = nm.replace(" ","_");
	 plist.add("Bdoc." + nm + ".javadoc.");
       }
    }
   BoardProperties bp = BoardProperties.getProperties("Bdoc");
   for (String s : bp.stringPropertyNames()) {
      boolean use = false;
      for (String ns : plist) {
	 if (s.startsWith(ns)) {
	    use = true;
	    break;
	  }
       }
      if (use) {
	 String nm = bp.getProperty(s);
	 if (nm != null) bdoc_props.add(s);
       }
    }

   handleRemoteAccess();

   File f = BoardSetup.getDocumentationFile();
   String cf = bp.getProperty("Bdoc.doc.file");
   if (cf != null) {
      File xf = new File(cf);
      if (xf.exists()) f = xf;
    }

   if (loadXml(f)) {
      ready_count = 0;
      return;
    }

   ready_count = 1;

   for (String s : bdoc_props) {
      String nm = bp.getProperty(s);
      if (nm != null) addJavadoc(nm);
    }

   noteSearcherDone();
}




/********************************************************************************/
/*										*/
/*	Setup methods								*/
/*										*/
/********************************************************************************/

void addJavadoc(String url)
{
   if (url == null) return;

   try {
      URL u = new URL(url);
      addJavadoc(u,null);
    }
   catch (MalformedURLException e) { }
}



void addJavadoc(URL u)			{ addJavadoc(u,null); }


synchronized void addJavadoc(URL u,String proj)
{
   Searcher s = new Searcher(u,proj);

   ++ready_count;

   BoardThreadPool.start(s);
}



private synchronized void noteSearcherDone()
{
   if (ready_count == 1) addHierarchyLinks();

   --ready_count;

   if (ready_count == 0) {
      notifyAll();
      File f = BoardSetup.getDocumentationFile();
      if (cache_repository) outputXml(f);
    }
}



/********************************************************************************/
/*										*/
/*	Access methods								*/
/*										*/
/********************************************************************************/

@Override public synchronized Iterable<BassName> getAllNames()
{
   waitForReady();

   ArrayList<BassName> rslt = new ArrayList<BassName>(all_items.values());

   rslt.addAll(inherited_items);

   return rslt;
}



@Override public boolean includesRepository(BassRepository br)	{ return br == this; }



BdocReference findReference(URL u)
{
   waitForReady();

   String uid = u.toExternalForm();

   return all_items.get(uid);
}



synchronized BdocReference findReferenceFromName(String name)
{
   waitForReady();

   for (BdocReference br : all_items.values()) {
      if (br.matchName(name)) return br;
    }

   //TODO: This might have to be more approximate

   return null;
}




synchronized void waitForReady()
{
   while (ready_count > 0) {
      try {
	 wait();
       }
      catch (InterruptedException e) { }
    }
}




/********************************************************************************/
/*										*/
/*	Loading methods 							*/
/*										*/
/********************************************************************************/

private void loadJavadoc(URL u,String p)
{
   URL u1;

   try {
      u1 = new URL(u,"index-all.html");
      if (loadJavadocFile(u1,p)) return;
    }
   catch (MalformedURLException e) {
      BoardLog.logE("BDOC","Bad javadoc url: " + e);
      return;
    }

   for (int i = 1; i <= 27; ++i) {
      try {
	 u1 = new URL(u,"index-files/index-" + i + ".html");
	 loadJavadocFile(u1,p);
       }
      catch (MalformedURLException e) { }
    }
}




private boolean loadJavadocFile(URL u,String p)
{
   BoardLog.logD("BDOC","Load documentation from " + u);

   try {
      URLConnection c = u.openConnection();
      InputStream ins = c.getInputStream();
      Reader inr = new BufferedReader(new InputStreamReader(ins));
      parser_delegator.parse(inr,new HtmlHandler(u,p),true);
      ins.close();
    }
   catch (IOException e) {
      return false;
    }

   return true;
}



private synchronized void addReference(BdocReference br)
{
   all_items.put(br.getReferenceUrl().toExternalForm(),br);
}




/********************************************************************************/
/*										*/
/*	HTML parser class							*/
/*										*/
/********************************************************************************/

enum HtmlState {
   NONE,
   ENTRY,
   DESCRIPTION
}

private class HtmlHandler extends HTMLEditorKit.ParserCallback
{
   private URL base_url;
   private String base_project;
   private BdocReference cur_reference;
   private HtmlState cur_state;
   private String ref_url;
   private String ref_title;

   HtmlHandler(URL u,String p) {
      base_url = u;
      base_project = p;
      cur_reference = null;
      cur_state = HtmlState.NONE;
    }

   @Override public void handleSimpleTag(HTML.Tag tag,MutableAttributeSet a,int pos) {
      handleStartTag(tag,a,pos);
    }

   @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet a,int pos) {
      if (tag == HTML.Tag.DT) {
	 cur_state = HtmlState.ENTRY;
	 cur_reference = null;
	 ref_url = null;
	 ref_title = null;
       }
      else if (tag == HTML.Tag.A && cur_state == HtmlState.ENTRY && ref_url == null) {
	 String href = getAttribute(HTML.Attribute.HREF,a);
	 if (href == null) cur_state = HtmlState.NONE;
	 else {
	    href = href.replace(" ","+");
	    ref_url = href;
	  }
	 ref_title = null;
       }
      else if (tag == HTML.Tag.DD && cur_state == HtmlState.ENTRY && ref_url != null) {
	 try {
	    cur_reference = new BdocReference(BdocRepository.this,base_project,base_url,ref_url,ref_title);
	    addReference(cur_reference);
	    cur_state = HtmlState.DESCRIPTION;
	  }
	 catch (BdocException e) {
	    BoardLog.logE("BDOC","Problem with javadoc reference: " + e);
	    cur_state = HtmlState.NONE;
	  }
       }
    }

   @Override public void handleEndTag(HTML.Tag tag,int pos)	{ }

   @Override public void handleText(char [] data,int pos) {
      if (cur_state == HtmlState.ENTRY) {
	 String s = new String(data);
	 if (ref_title == null) ref_title = s;
	 else ref_title += s;
       }
      else if (cur_state == HtmlState.DESCRIPTION) {
	 cur_reference.addDescription(new String(data));
       }
    }

   private String getAttribute(HTML.Attribute k,MutableAttributeSet a) {
      return (String) a.getAttribute(k);
    }

}	// end of inner class HtmlHandler




/********************************************************************************/
/*										*/
/*	Methods to handle hierarchical links					*/
/*										*/
/********************************************************************************/

private void addHierarchyLinks()
{
   Set<String> pkgs = new HashSet<String>();
   Set<String> clss = new HashSet<String>();

   for (BdocReference br : all_items.values()) {
      switch (br.getNameType()) {
	 case PACKAGE :
	    pkgs.add(br.getDigestedName());
	    break;
	 case CLASS :
	 case INTERFACE :
	 // case ENUM :
	    clss.add(br.getDigestedName());
	    break;
	 default :
	    break;
       }
    }

   BumpClient bc = BumpClient.getBump();
   Hierarchy hier = new Hierarchy();

   for (String pkg : pkgs) {
      Element xml = bc.getTypeHierarchy(null,pkg,null,false);
      hier.loadRelations(xml);
    }

   MemberInfo mif = new MemberInfo();
   for (BdocReference br : all_items.values()) {
      mif.addReference(br);
    }

   for (String cls : hier.getClasses()) {
      if (!clss.contains(cls)) continue;
      Set<String> done = new HashSet<String>();
      for (String scls : hier.getSupers(cls)) {
	 if (scls.equals("java.lang.Object")) continue;
	 for (String mthd : mif.getMethods(scls)) {
	    if (!done.contains(mthd) && mif.getReferences(cls,mthd) == null) {
	       done.add(mthd);
	       for (BdocReference base : mif.getReferences(scls,mthd)) {
		  BdocInheritedReference bir = new BdocInheritedReference(base,cls);
		  inherited_items.add(bir);
		}
	     }
	  }
       }
    }
}




private static class Hierarchy {

   private Map<String,Set<String>>	class_hierarchy;

   Hierarchy() {
      class_hierarchy = new HashMap<String,Set<String>>();
    }

   Collection<String> getClasses()		{ return class_hierarchy.keySet(); }

   Collection<String> getSupers(String cls)	{ return class_hierarchy.get(cls); }

   void loadRelations(Element xml) {
      for (Element ce : IvyXml.children(xml,"TYPE")) {
	 String nm = IvyXml.getAttrString(ce,"NAME");

	 Set<String> sups = class_hierarchy.get(nm);
	 if (sups == null) {
	    sups = new LinkedHashSet<String>();
	    class_hierarchy.put(nm,sups);
	  }

	 // String k = IvyXml.getAttrString(ce,"KIND");
	 // might want to restrict to classes
	 for (Element se : IvyXml.children(ce,"SUPERTYPE")) {
	    String sn = IvyXml.getAttrString(se,"NAME");
	    sups.add(sn);
	  }
	 for (Element se : IvyXml.children(ce,"EXTENDIFACE")) {
	    String sn = IvyXml.getAttrString(se,"NAME");
	    sups.add(sn);
	  }
       }
    }

}	// end of inner class Hierarchy




private static class MemberInfo {

   private Map<String,Map<String,List<BdocReference>>> ref_byclass;

   MemberInfo() {
      ref_byclass = new HashMap<String,Map<String,List<BdocReference>>>();
    }

   Collection<String> getMethods(String cls) {
      Map<String,List<BdocReference>> mls = ref_byclass.get(cls);
      if (mls == null) return new ArrayList<String>();
      return mls.keySet();
    }

   List<BdocReference> getReferences(String cls,String mthd) {
      Map<String,List<BdocReference>> mls = ref_byclass.get(cls);
      if (mls == null) return null;
      return mls.get(mthd);
    }

   void addReference(BdocReference br) {
      switch (br.getNameType()) {
	 case METHOD :
	    break;
	 case FIELDS :
	    return;				// might want to do inherited fields
	 case CONSTRUCTOR :
	    return;
	 default :
	    return;
       }

      String nm = br.getDigestedName(); 	// without parameters
      int idx = nm.lastIndexOf(".");
      if (idx < 0) return;
      String cls = nm.substring(0,idx);
      String itm = nm.substring(idx+1);
      Map<String,List<BdocReference>> mems = ref_byclass.get(cls);
      if (mems == null) {
	 mems = new HashMap<String,List<BdocReference>>();
	 ref_byclass.put(cls,mems);
       }
      List<BdocReference> refs = mems.get(itm);
      if (refs == null) {
	 refs = new ArrayList<BdocReference>(2);
	 mems.put(itm,refs);
       }
      refs.add(br);
    }

}	// end of inner class MemberInfo




/********************************************************************************/
/*										*/
/*	Input/Output methods							*/
/*										*/
/********************************************************************************/

private void outputXml(File f)
{
   try {
      IvyXmlWriter xw = new IvyXmlWriter(f);

      xw.outputHeader();

      xw.begin("JAVADOC");
      xw.field("WHEN",System.currentTimeMillis());

      BoardProperties bp = BoardProperties.getProperties("Bdoc");
      for (String s : bdoc_props) {
	 String nm = bp.getProperty(s);
	 if (nm != null) xw.textElement("SOURCE",nm);
       }

      for (BdocReference br : all_items.values()) {
	 br.outputXml(xw);
       }

      for (BdocInheritedReference ibr : inherited_items) {
	 ibr.outputXml(xw);
       }

      xw.end("JAVADOC");

      xw.close();
    }
   catch (IOException e) {
      BoardProperties bpx = BoardProperties.getProperties("System");
      BoardLog.logE("BDOC","Problem outputing repository: " + f + " " +
		       BoardSetup.getPropertyBase() + " " +
		       bpx.getProperty("edu.brown.cs.bubbles.workspace") + " " +
		       bpx.getProperty("edu.brown.cs.bubbles.install"),e);
    }
}


private boolean loadXml(File f)
{
   if (!f.exists()) return false;

   Set<String> sources = new HashSet<String>();
   BoardProperties bp = BoardProperties.getProperties("Bdoc");
   boolean check = bp.getBoolean("Bdoc.check.dates",true);

   long dlm = f.lastModified();
   for (String pnm : bdoc_props) {
      String nm = bp.getProperty(pnm);
      if (nm != null) {
	 sources.add(nm);
	 try {
	    URI u = new URI(nm);
	    if (u.getScheme().equals("file")) {
	       File f0 = new File(u.getPath());
	       if (!f0.exists() || (check && f0.lastModified() > dlm)) {
		  BoardLog.logD("BDOC","Update doc because of " + f0);
		  return false;
		}
	     }
	    else if (u.getScheme().equals("http")) {
	       try {
		  URL url = u.toURL();
		  HttpURLConnection c = (HttpURLConnection) url.openConnection();
		  c.setIfModifiedSince(dlm);
		  c.setInstanceFollowRedirects(true);
		  int cd = c.getResponseCode();
		  if (cd == HttpURLConnection.HTTP_OK) {
		     long ndlm = c.getLastModified();
		     if (check && ndlm > dlm) {
			BoardLog.logD("BDOC","Update doc because of " + u);
			return false;
		      }
		   }
		  else if (cd >= 400) {
		     BoardLog.logD("BDOC","Update doc because of error " + cd + " on " + u);
		     return false;
		   }
		}
	       catch (IOException e) {
		  // ignore bad connection -- use cached value
		}
	     }
	  }
	 catch (URISyntaxException e) {
	    BoardLog.logD("BDOC","Update doc because of uri syntax error " + e);
	    return false;
	  }
       }
     }

   SAXParserFactory spf = SAXParserFactory.newInstance();
   spf.setValidating(false);
   spf.setXIncludeAware(false);
   spf.setNamespaceAware(false);

   BdocLoader ldr = new BdocLoader(sources);

   try {
      SAXParser sp = spf.newSAXParser();
      FileInputStream fis = new FileInputStream(f);
      InputSource ins = new InputSource(fis);
      ins.setEncoding("UTF-8");
      sp.parse(ins,ldr);
      fis.close();
    }
   catch (SAXException e) {
      BoardLog.logE("BDOC","Problem parsing saved repository");
      return false;
    }
   catch (ParserConfigurationException e) {
      BoardLog.logE("BDOC","Problem configuring parser",e);
      return false;
    }
   catch (IOException e) {
      BoardLog.logE("BDOC","Problem reading saved repository",e);
      return false;
    }

   return ldr.isValid();
}



/********************************************************************************/
/*										*/
/*	Handle remote access of documentation file				*/			
/*										*/
/********************************************************************************/

private void handleRemoteAccess()
{
   BoardSetup bs = BoardSetup.getSetup();

   switch (bs.getRunMode()) {
      case CLIENT :
	 BumpClient bc = BumpClient.getBump();
	 File f = BoardSetup.getDocumentationFile();
	 if (f.exists()) break;
	 bc.getRemoteFile(f,"BDOC",null);
	 // ignore failures -- we'll just load the doc ourselves
	 break;
      default:
	 break;
    }
}





/********************************************************************************/
/*										*/
/*	Searcher thread 							*/
/*										*/
/********************************************************************************/

private class Searcher implements Runnable {

   private URL base_url;
   private String for_project;

   Searcher(URL u,String p) {
      base_url = u;
      for_project = p;
    }

   @Override public void run() {
      loadJavadoc(base_url,for_project);
      noteSearcherDone();
    }

   @Override public String toString() {
      return "BDOC_Searcher_" + base_url;
    }

}	// end of inner class Searcher



/********************************************************************************/
/*										*/
/*	BdocLoader class to handle XML parsing					*/
/*										*/
/********************************************************************************/

enum TextItem {
   NONE, SOURCE, URL, DESCRIPTION
}



private class BdocLoader extends DefaultHandler {

   private Map<String,BdocReference> base_map;
   private Set<String> source_list;
   private TextItem cur_text;
   private String doc_type;
   private String doc_name;
   private String doc_params;
   private String doc_url;
   private String doc_description;
   private String doc_project;
   private StringBuffer cur_buffer;
   private Boolean is_valid;

   BdocLoader(Set<String> sources) {
      source_list = sources;
      cur_text = TextItem.NONE;
      cur_buffer = null;
      is_valid = null;
      base_map = new HashMap<String,BdocReference>();
    }

   boolean isValid() {
      return is_valid == Boolean.TRUE;
    }

   @Override public void startElement(String uri,String lnm,String qnm,Attributes attrs) {
      if (qnm.equals("SOURCE")) {
	 cur_text = TextItem.SOURCE;
       }
      else if (qnm.equals("BDOC")) {
	 if (is_valid == null) {
	    is_valid = Boolean.valueOf(source_list.isEmpty());
	  }
	 if (is_valid) {
	    doc_type = attrs.getValue("TYPE");
	    doc_name = attrs.getValue("NAME");
	    doc_params = attrs.getValue("PARAMS");
	    doc_project = attrs.getValue("PROJECT");
	  }
       }
      else if (is_valid == Boolean.TRUE) {
	 if (qnm.equals("INHERIT")) {
	    String nm = attrs.getValue("NAME");
	    String prms = attrs.getValue("PARAMS");
	    String base = attrs.getValue("BASE");
	    String k = base;
	    if (prms != null) k += prms;
	    BdocReference br = base_map.get(k);
	    int idx = nm.lastIndexOf(".");
	    if (idx >= 0 && br != null) {
	       String cls = nm.substring(0,idx);
	       BdocInheritedReference bir = new BdocInheritedReference(br,cls);
	       inherited_items.add(bir);
	     }
	  }
	 else if (qnm.equals("URL")) cur_text = TextItem.URL;
	 else if (qnm.equals("DESCRIPTION")) cur_text = TextItem.DESCRIPTION;
       }
    }

   @Override public void endElement(String uri,String lnm,String qnm) {
      switch (cur_text) {
	 case NONE :
	    break;
	 case SOURCE :
	    if (cur_buffer != null) {
	       String src = cur_buffer.toString();
	       if (!source_list.remove(src)) is_valid = false;
	     }
	    break;
	 case URL :
	    if (cur_buffer != null) doc_url = cur_buffer.toString();
	    break;
	 case DESCRIPTION :
	    if (cur_buffer != null) doc_description = cur_buffer.toString();
	    break;
       }
      cur_text = TextItem.NONE;
      cur_buffer = null;
      if (qnm.equals("BDOC")) {
	 if (is_valid) {
	    BdocReference br = new BdocReference(BdocRepository.this,
						    doc_type,doc_name,doc_params,
						    doc_description,doc_project,doc_url);
	    addReference(br);
	    String k = br.getDigestedName();
	    if (br.getParameters() != null) k += br.getParameters();
	    base_map.put(k,br);
	  }
       }
    }

   @Override public void characters(char [] ch,int start,int len) {
      if (cur_text != TextItem.NONE) {
	 if (cur_buffer == null) cur_buffer = new StringBuffer();
	 cur_buffer.append(ch,start,len);
       }
    }

}	// end of inner class BdocLoader




}	// end of class BdocRepository




/* end of BdocRepository.java */
