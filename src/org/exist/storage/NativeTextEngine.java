/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2001-04 Wolfgang M. Meier
 *  wolfgang@exist-db.org
 *  http://exist-db.org
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 * 
 *  $Id$
 */
package org.exist.storage;

import java.io.EOFException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.exist.EXistException;
import org.exist.collections.Collection;
import org.exist.dom.AttrImpl;
import org.exist.dom.DocumentImpl;
import org.exist.dom.DocumentSet;
import org.exist.dom.ElementImpl;
import org.exist.dom.ExtArrayNodeSet;
import org.exist.dom.Match;
import org.exist.dom.NodeImpl;
import org.exist.dom.NodeProxy;
import org.exist.dom.NodeSet;
import org.exist.dom.TextImpl;
import org.exist.dom.XMLUtil;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.analysis.TextToken;
import org.exist.storage.btree.BTreeCallback;
import org.exist.storage.btree.BTreeException;
import org.exist.storage.btree.DBException;
import org.exist.storage.btree.IndexQuery;
import org.exist.storage.btree.Value;
import org.exist.storage.index.BFile;
import org.exist.storage.io.VariableByteArrayInput;
import org.exist.storage.io.VariableByteInput;
import org.exist.storage.io.VariableByteOutputStream;
import org.exist.storage.lock.Lock;
import org.exist.storage.serializers.Serializer;
import org.exist.util.ByteArray;
import org.exist.util.ByteConversion;
import org.exist.util.Configuration;
import org.exist.util.LockException;
import org.exist.util.Occurrences;
import org.exist.util.ProgressIndicator;
import org.exist.util.ReadOnlyException;
import org.exist.util.UTF8;
import org.exist.util.XMLString;
import org.exist.xquery.TerminatedException;
import org.exist.xquery.XQueryContext;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * This class is responsible for fulltext-indexing. Text-nodes are handed over
 * to this class to be fulltext-indexed. Method storeText() is called by
 * RelationalBroker whenever it finds a TextNode. Method getNodeIDsContaining()
 * is used by the XPath-engine to process queries where a fulltext-operator is
 * involved. The class keeps two database tables: table words stores the words
 * found with their unique id. Table inv_idx contains the word occurrences for
 * every word-id per document.
 * 
 * TODO: store node type (attribute or text) with each entry
 * 
 * @author Wolfgang Meier
 */
public class NativeTextEngine extends TextSearchEngine implements ContentLoadingObserver {

	public final static byte ATTRIBUTE_SECTION = 1;
	public final static byte TEXT_SECTION = 0;
  
  /**
   * Limit the length of the words to be indexed.
   * Default is 512 characters for words in attributes and elements. 
   */
  public final static int MAX_WORD_LENGTH = 2048;
	
	protected BFile dbWords;
	protected InvertedIndex invIdx;

	public NativeTextEngine(DBBroker broker, Configuration config, BFile db) {
		super(broker, config);
        this.dbWords = db;
        this.invIdx = new InvertedIndex();
	}

	/**
	 * check if string contains non-letters (maybe it's a regular expression?
	 * 
	 * @param str
	 *                Description of the Parameter
	 * @return Description of the Return Value
	 */
	public final static boolean containsWildcards(String str) {
		for (int i = 0; i < str.length(); i++)
			switch (str.charAt(i)) {
				case '*' :
				case '?' :
				case '\\' :
				case '[' :
				case ']' :
					return true;
			}
		return false;
	}

	public final static boolean startsWithWildcard(String str) {
		if (str == null || str.length() == 0)
			return false;
		switch (str.charAt(0)) {
			case '*' :
			case '?' :
			case '\\' :
			case '[' :
				return true;
		}
		return false;
	}

	public void close() {
		try {
			dbWords.close();
		} catch (DBException dbe) {
			LOG.debug(dbe);
		}
	}

	public int getTrackMatches() {
		return trackMatches;
	}
	
	public void setTrackMatches(int flags) {
		trackMatches = flags;
	}
	
	/**
	 * Collect all words in a document to be removed
	 * 
	 * @param words
	 *                Description of the Parameter
	 * @param domIterator
	 *                Description of the Parameter
	 */
	protected void collect(Set words, Iterator domIterator) {
		byte[] data = ((Value) domIterator.next()).getData();
		short type = Signatures.getType(data[0]);
		String word;
		TextToken token;
		switch (type) {
			case Node.ELEMENT_NODE :
				int children = ByteConversion.byteToInt(data, 1);
				for (int i = 0; i < children; i++)
					collect(words, domIterator);
				break;
			case Node.TEXT_NODE :
				String s;
				try {
					s = new String(data, 1, data.length - 1, "UTF-8");
				} catch (UnsupportedEncodingException uee) {
					s = new String(data, 1, data.length - 1);
				}
				tokenizer.setText(s);
				while (null != (token = tokenizer.nextToken())) {
					word = token.getText();
					if (stoplist.contains(word))
						continue;
					words.add(word.toLowerCase());
				}
				break;
			case Node.ATTRIBUTE_NODE :
				byte idSizeType = (byte) (data[0] & 0x3);
				String val;
				try {
					val = new String(data,
							1 + Signatures.getLength(idSizeType), data.length
									- 1 - Signatures.getLength(idSizeType),
							"UTF-8");
				} catch (UnsupportedEncodingException uee) {
					val = new String(data,
							1 + Signatures.getLength(idSizeType), data.length
									- 1 - Signatures.getLength(idSizeType));
				}
				tokenizer.setText(val);
				while (null != (token = tokenizer.nextToken())) {
					word = token.getText().toString();
					if (stoplist.contains(word))
						continue;
					words.add(word.toLowerCase());
				}
				break;
		}
	}

	public void flush() {
		invIdx.flush();
	}

	public void reindex(DocumentImpl oldDoc, NodeImpl node) {
		invIdx.reindex(oldDoc, node);
	}

	public void remove() {
		invIdx.remove();
	}

	public NodeSet getNodesContaining(XQueryContext context, DocumentSet docs, NodeSet contextSet,
										String expr, int type) throws TerminatedException {
		if (type == DBBroker.MATCH_EXACT && containsWildcards(expr)) {
			type = DBBroker.MATCH_WILDCARDS;
		}
		switch (type) {
			case DBBroker.MATCH_EXACT :
				return getNodesExact(context, docs, contextSet, expr);
			default :
				return getNodesRegexp(context, docs, contextSet, expr, type);
		}
	}

	/**
	 * Get all nodes whose content exactly matches the terms passed in expr.
	 * Called by method getNodesContaining.
	 * 
	 * @param the
	 *                input document set
	 * @param array
	 *                of regular expression search terms
	 * @return array containing a NodeSet for each of the search terms
	 */
	public NodeSet getNodesExact(XQueryContext context, DocumentSet docs, NodeSet contextSet, String expr) 
	throws TerminatedException {
		if (expr == null)
			return null;
		if (stoplist.contains(expr))
			return null;
//				long start = System.currentTimeMillis();
		DocumentImpl doc;
		Value ref;
//		byte[] data;
		long gid;
		int docId;
		int len, rawSize;
		int section;
		int sizeHint = -1;
		long last;
		int freq = 1;
		Collection collection;
		short collectionId;
		VariableByteInput is = null;
		NodeProxy parent, current = new NodeProxy();
		Match match;
		NodeSet result = new ExtArrayNodeSet(docs.getLength(), 250);
		String term = (stem) ? stemmer.stem(expr.toLowerCase()) : expr
				.toLowerCase();
		int count = 0;
		for (Iterator iter = docs.getCollectionIterator(); iter.hasNext();) {
			collection = (Collection) iter.next();
			collectionId = collection.getId();
			ref = new WordRef(collectionId, term);
			Lock lock = dbWords.getLock();
			try {
				lock.acquire();
				is = dbWords.getAsStream(ref);
				if (is == null) {
					continue;
				}
				while (is.available() > 0) {
					docId = is.readInt();
					section = is.readByte();
					len = is.readInt();
					rawSize = is.readFixedInt();
					if ((doc = docs.getDoc(docId)) == null
							|| (contextSet != null && !contextSet.containsDoc(doc))) {
						is.skipBytes(rawSize);
						continue;
					}
					if (contextSet != null)
						sizeHint = contextSet.getSizeHint(doc);
					last = 0;
					for (int j = 0; j < len; j++) {
						gid = last + is.readLong();
						if(termFreq)
							freq = is.readInt();
						last = gid;
						count++;
						current = (section == TEXT_SECTION ? new NodeProxy(
								doc, gid, Node.TEXT_NODE) : new NodeProxy(
								doc, gid, Node.ATTRIBUTE_NODE));
						// if a context set is specified, we can directly check if the
						// matching text node is a descendant of one of the nodes
						// in the context set.
						if (contextSet != null) {
							if (section == TEXT_SECTION)
								parent = contextSet.parentWithChild(current, false,
									true, -1);
							else
								parent = contextSet.get(current);
							if (parent != null) {
								match = new Match(term, gid);
								match.setFrequency(freq);
								result.add(parent, sizeHint);
								if (trackMatches != Serializer.TAG_NONE)
									parent.addMatch(match);
							}
						// otherwise, we add all text nodes without check
						} else {
							result.add(current, sizeHint);
						}
						context.proceed();
					}
				}
			} catch (EOFException e) {
			} catch (LockException e) {
				LOG.warn("could not acquire lock on words db", e);
			} catch (IOException e) {
				LOG.warn("io error while reading words", e);
			} finally {
				lock.release();
			}
		}
		if (contextSet != null)
			((ExtArrayNodeSet) result).sort();
//				LOG.debug(
//					"found "
//						+ expr
//						+ ": "
//						+ result.getLength()
//						+ " ("
//						+ count
//						+ ") "
//						+ " in "
//						+ (System.currentTimeMillis() - start)
//						+ "ms.");
		return result;
	}

	private NodeSet getNodesRegexp(XQueryContext context, DocumentSet docs, NodeSet contextSet,
									String expr, int type) throws TerminatedException {
		if (expr == null)
			return null;
		if (stoplist.contains(expr))
			return null;
		expr = expr.toLowerCase();
		StringBuffer term = new StringBuffer();
		for (int j = 0; j < expr.length(); j++)
			if (Character.isLetterOrDigit(expr.charAt(j)))
				term.append(expr.charAt(j));
			else
				break;
		try {
			TermMatcher comparator = new RegexMatcher(expr, type, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
			return getNodes(context, docs, contextSet, comparator, term);
		} catch (EXistException e) {
			return null;
		}
	}

	/**
	 * Return all nodes whose content matches any of the search terms in expr.
	 * This method interprets the search terms as regular expressions and
	 * matches them against all indexed words.
	 * 
	 * @param the
	 *                input document set
	 * @param array
	 *                of regular expression search terms
	 * @return array containing a NodeSet for each of the search terms
	 */
	public NodeSet getNodes(XQueryContext context, DocumentSet docs, NodeSet contextSet,
							TermMatcher matcher, CharSequence startTerm) throws TerminatedException {
//		long start = System.currentTimeMillis();
		NodeSet result = new ExtArrayNodeSet();
		Value ref;
		Collection collection;
		short collectionId;
		Lock lock = dbWords.getLock();
		SearchCallback cb = new SearchCallback(context, matcher, result, contextSet, docs);
		for (Iterator iter = docs.getCollectionIterator(); iter.hasNext();) {
			collection = (Collection) iter.next();
			collectionId = collection.getId();
			if (startTerm != null && startTerm.length() > 0)
				ref = new WordRef(collectionId, startTerm.toString().toLowerCase());
			else
				ref = new WordRef(collectionId);
			IndexQuery query = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
			try {
				lock.acquire();
				try {
					dbWords.query(query, cb);
				} catch (IOException ioe) {
					LOG.debug(ioe);
				} catch (BTreeException bte) {
					LOG.debug(bte);
				}
			} catch (LockException e) {
				LOG.debug(e);
			} finally {
				lock.release();
			}
		}
//		LOG.debug("regexp found: " + result.getLength() + " in "
//				+ (System.currentTimeMillis() - start) + "ms.");
		return result;
	}

	public String[] getIndexTerms(DocumentSet docs, TermMatcher matcher) {
//		long start = System.currentTimeMillis();
		Value ref;
		Collection collection;
		short collectionId;
		Lock lock = dbWords.getLock();
		IndexCallback cb = new IndexCallback(null, matcher);
		for (Iterator iter = docs.getCollectionIterator(); iter.hasNext();) {
			collection = (Collection) iter.next();
			collectionId = collection.getId();
			ref = new WordRef(collectionId);
			IndexQuery query = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
			try {
				lock.acquire();
				try {
					dbWords.query(query, cb);
				} catch (IOException ioe) {
					LOG.debug(ioe);
				} catch (BTreeException bte) {
					LOG.debug(bte);
				} catch (TerminatedException e) {
                    LOG.debug(e);
                }
			} catch (LockException e) {
				LOG.debug(e);
			} finally {
				lock.release();
			}
		}
		return cb.getMatches();
	}

	public Occurrences[] scanIndexTerms(DocumentSet docs, NodeSet contextSet,
			String start, String end) throws PermissionDeniedException {
		long t0 = System.currentTimeMillis();
		final Lock lock = dbWords.getLock();
		short collectionId;
		Collection current;
		IndexQuery query;
		IndexScanCallback cb = new IndexScanCallback(docs, contextSet);
		for (Iterator i = docs.getCollectionIterator(); i.hasNext();) {
			current = (Collection) i.next();
			collectionId = current.getId();

            if (end == null)
                query = new IndexQuery(IndexQuery.TRUNC_RIGHT, new WordRef(collectionId, start.toLowerCase()));
            else
    			query = new IndexQuery(IndexQuery.BW, new WordRef(collectionId,
    					start.toLowerCase()), new WordRef(collectionId, end.toLowerCase()));
			try {
				lock.acquire();
				dbWords.query(query, cb);
			} catch (LockException e) {
				LOG.warn("cannot get lock on words", e);
			} catch (IOException e) {
				LOG.warn("error while reading words", e);
			} catch (BTreeException e) {
				LOG.warn("error while reading words", e);
			} catch (TerminatedException e) {
                LOG.warn("Method terminated", e);
            } finally {
				lock.release();
			}
		}
		Map map = cb.map;
		Occurrences[] result = new Occurrences[map.size()];
		LOG.debug("Found " + result.length + " in " + (System.currentTimeMillis() - t0) + "ms");
		return (Occurrences[]) map.values().toArray(result);
	}

	/**
	 * Remove indexed words for entire collection
	 * 
	 * @param collection
	 *                Description of the Parameter
	 */
	public void dropIndex(Collection collection) {
		Lock lock = dbWords.getLock();
		try {
			lock.acquire(Lock.WRITE_LOCK);
			LOG.debug("removing fulltext index ...");
			WordRef ref = new WordRef(collection.getId());
			IndexQuery query = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
			dbWords.flush();
			dbWords.removeAll(query);
		} catch (BTreeException bte) {
			LOG.debug(bte);
		} catch (IOException ioe) {
			LOG.debug(ioe);
		} catch (DBException dbe) {
			LOG.warn(dbe);
		} catch (LockException e) {
			LOG.warn("Failed to acquire lock on collections.dbx", e);
		} finally {
			lock.release(Lock.WRITE_LOCK);
		}
	}
	
	/**
	 * Remove all index entries for the specified document
	 * 
	 * @param doc
	 *                The document
	 */
	public void dropIndex(DocumentImpl doc) {
		try {
			TreeSet words = new TreeSet();
			NodeList children = doc.getChildNodes();
			NodeImpl node;
			for (int i = 0; i < children.getLength(); i++) {
				node = (NodeImpl) children.item(i);
				Iterator j = broker.getDOMIterator(new NodeProxy(doc, node
						.getGID(), node.getInternalAddress()));
				collect(words, j);
			}
			String word;
//			Value val;
			WordRef ref;
			VariableByteInput is = null;
			VariableByteOutputStream os;
			int len, rawSize;
			int docId;
			byte section;
			short collectionId = doc.getCollection().getId();
			boolean changed;
			Lock lock = dbWords.getLock();
			for (Iterator iter = words.iterator(); iter.hasNext();) {
				word = (String) iter.next();
				ref = new WordRef(collectionId, word);
				try {
					lock.acquire(Lock.WRITE_LOCK);
					is = dbWords.getAsStream(ref);
					if (is == null) {
						continue;
					}
					os = new VariableByteOutputStream();
					changed = false;
					try {
						while (is.available() > 0) {
							docId = is.readInt();
							section = is.readByte();
							len = is.readInt();
							rawSize = is.readFixedInt();
							if (docId != doc.getDocId()) {
								// copy data to new buffer
								os.writeInt(docId);
								os.writeByte(section);
								os.writeInt(len);
								os.writeFixedInt(rawSize);
								is.copyRaw(os, rawSize);
							} else {
								changed = true;
								// skip
								is.skipBytes(rawSize);
							}
						}
					} catch (EOFException e) {
						//				    LOG.debug(e.getMessage(), e);
					} catch (IOException e) {
						//				    LOG.debug(e.getMessage(), e);
					}
					if (changed) {
						if (os.data().size() == 0) {
							dbWords.remove(ref);
						} else {
							if (dbWords.put(ref, os.data()) < 0 && LOG.isDebugEnabled()) {
									LOG.debug("removeDocument() - "
											+ "could not remove index for "
											+ word);
							}
						}
					}
				} catch (LockException e) {
					LOG.warn("removeDocument(DocumentImpl) - "
							+ "could not acquire lock on words db", e);
					is = null;
				} catch (IOException e) {
					LOG.error("removeDocument(DocumentImpl) - "
							+ "io error while reading words", e);
					is = null;
				} finally {
					lock.release();
				}
			}
            if (LOG.isDebugEnabled()) {
                LOG.debug("removeDocument() - "
                    + words.size()
                    + " words updated.");
            }
		} catch (ReadOnlyException e) {
            LOG.warn("removeDocument(DocumentImpl) - "
                + "database is read-only");
		}
	}

	/**
	 * Index an attribute value
	 * 
	 * @param attr
	 *                the attribute to be indexed
	 */
	public void storeAttribute(FulltextIndexSpec idx, AttrImpl attr) {
		final DocumentImpl doc = (DocumentImpl) attr.getOwnerDocument();
		tokenizer.setText(attr.getValue());
		String word;
		TextToken token;
		final long gid = attr.getGID();
		while (null != (token = tokenizer.nextToken())) {
			if (idx != null && idx.getIncludeAlphaNum() == false
					&& token.getType() == TextToken.ALPHANUM) {
				continue;
			}
			word = token.getText().toLowerCase();
			if (stoplist.contains(word) || word.length() > MAX_WORD_LENGTH) {
				continue;
			}
			invIdx.setDocument(doc);
			invIdx.addAttribute(word, gid);
		}
	}

	/**
	 * Index a text node
	 * 
	 * @param idx
	 *                IndexPaths object passed in by the broker
	 * @param text
	 *                the text node to be indexed
	 * @param onetoken
	 *                if true, given text is indexed as a whole
	 *                if false, it is indexed token by token
	 * @return boolean indicates if all of the text content has been added to
	 *            the index
	 */
	public void storeText(FulltextIndexSpec idx, TextImpl text, boolean onetoken) {
		final DocumentImpl doc = (DocumentImpl) text.getOwnerDocument();
		tokenizer.setText(text.getXMLString().transformToLower());
		TextToken token;
		final long gid = text.getGID();
		if ( onetoken ) {
			invIdx.setDocument(doc);
			String sal= text.getXMLString().transformToLower().toString();
            token = new TextToken(TextToken.ALPHA, sal, 0, sal.length());
			invIdx.addText(token, gid);			
		} else {
			while (null != (token = tokenizer.nextToken())) {
				if (idx != null && idx.getIncludeAlphaNum() == false
						&& token.isAlpha() == false) {
					continue;
				}
				if (stoplist.contains(token) || token.length() > MAX_WORD_LENGTH) {
					continue;
				}
				invIdx.setDocument(doc);
				invIdx.addText(token, gid);
			}
		}
	}

	public void sync() {
		// uncomment this to get statistics about page buffer usage
		dbWords.printStatistics();
		Lock lock = dbWords.getLock();
		try {
			lock.acquire(Lock.WRITE_LOCK);
			try {
				dbWords.flush();
			} catch (DBException dbe) {
				LOG.warn(dbe);
			}
		} catch (LockException e) {
			LOG.warn("could not acquire lock on words db", e);
		} finally {
			lock.release();
		}
	}

	/**
	 * This inner class is responsible for actually storing the list of
	 * occurrences.
	 * 
	 * @author Wolfgang Meier <meier@ifs.tu-darmstadt.de>
	 */
	final class InvertedIndex {

		private DocumentImpl doc = null;
		private Map words[] = new HashMap[2];
		private VariableByteOutputStream os = new VariableByteOutputStream(7);

		public InvertedIndex() {
			// To distinguish between attribute values and text, we use
			// two maps: words[0] collects text, words[1] stores attribute
			// values.
			words[0] = new HashMap(512);
			words[1] = new HashMap(256);
		}

		public void addText(TextToken token, long gid) {
			TermFrequencyList buf = (TermFrequencyList) words[0].get(token);
			if (buf == null) {
				buf = new TermFrequencyList();
				buf.add(gid);
				words[0].put(token.getText(), buf);
			} else if (buf.getLast() == gid) {
				buf.incLastTerm();
			} else {
				buf.add(gid);
			}
		}

		public void addAttribute(String word, long gid) {
			TermFrequencyList buf = (TermFrequencyList) words[1].get(word);
			if (buf == null) {
				buf = new TermFrequencyList();
				buf.add(gid);
				words[1].put(word, buf);
			} else if (buf.getLast() == gid) {
				buf.incLastTerm();
			} else
				buf.add(gid);
		}

		/**
		 * Remove the entries in the current list from the index.
		 */
		public void remove() {
		    // TODO: use VariableInputStream
			if (doc == null)
				return;
			final short collectionId = doc.getCollection().getId();
			int len, rawSize, docId;
			Map.Entry entry;
			String word;
			TermFrequencyList idList;
			TermFrequencyList.TermFreq[] ids;
			byte[] data;
			long last; // , gid;
			long delta;
			byte section;
//			NodeProxy p;
			WordRef ref;
			TermFrequencyList newList;
			int freq = 1;
			Value val = null;
			VariableByteArrayInput is;
			Lock lock = dbWords.getLock();
			for (int k = 0; k < 2; k++) {
				for (Iterator i = words[k].entrySet().iterator(); i.hasNext();) {
					entry = (Map.Entry) i.next();
					word = (String) entry.getKey();
					idList = (TermFrequencyList) entry.getValue();
					ref = new WordRef(collectionId, word);
					try {
					    lock.acquire(Lock.WRITE_LOCK);
					    val = dbWords.get(ref);
					    os.clear();
					    // new output list containing nodes from the
					    // document that should not be removed
					    newList = new TermFrequencyList();
					    if (val != null) {
					        // add old entries to the new list
					        data = val.getData();
//					        LOG.debug("old size: " + data.length);
					        is = new VariableByteArrayInput(data);
					        try {
					            while (is.available() > 0) {
					                docId = is.readInt();
					                section = is.readByte();
					                len = is.readInt();
					                rawSize = is.readFixedInt();
					                if (docId == doc.getDocId() && section == k) {
					                    // copy data to new output list; skip
					                    // removed nodes
					                    last = 0;
					                    for (int j = 0; j < len; j++) {
					                        last = last + is.readLong();
					                        if(termFreq)
					                        	freq = is.readInt();
					                        // add the node to the new output list if it is not found
					                        // in the list of removed nodes
					                        if (!idList.contains(last)) {
					                            newList.add(last);
					                            newList.setLastTermFreq(freq);
					                        }
					                    }
					                } else {
					                    // section belongs to another document:
					                    // copy data to new buffer
					                    os.writeInt(docId);
					                    os.writeByte(section);
					                    os.writeInt(len);
					                    os.writeFixedInt(rawSize);
					                    is.copyRaw(os, rawSize);
					                }
					            }
					        } catch (EOFException e) {
					            LOG
					            .error("end-of-file while reading index entry for "
					                    + word);
					        } catch (IOException e) {
					            LOG.error("io-error while reading index entry for "
					                    + word);
					        }
					    }
					    if(newList.getSize() > 0) {
					    	// save the nodes remaining in the output list for the document
						    ids = newList.toArray();

						    Arrays.sort(ids);
						    len = ids.length;
						    os.writeInt(doc.getDocId());
						    os.writeByte(k == 0 ? TEXT_SECTION : ATTRIBUTE_SECTION);
						    os.writeInt(len);
						    rawSize = os.position();
		                    os.writeFixedInt(0);
		                    
						    last = 0;
						    for (int j = 0; j < len; j++) {
						        delta = ids[j].l - last;
						        if (delta < 0) {
						            LOG.debug("neg. delta: " + delta + " for " + word);
						            LOG.debug("id = " + ids[j] + "; prev = " + last);
						        }
						        os.writeLong(delta);
						        if(termFreq)
						        	os.writeInt(ids[j].count);
						        last = ids[j].l;
						    }
						    os.writeFixedInt(rawSize, os.position() - rawSize - 4);
					    }
					    ByteArray ndata = os.data();
//					    LOG.debug("new size: " + ndata.size());
					    if(ndata.size() == 0) {
					    	try {
								dbWords.remove(ref);
							} catch (ReadOnlyException e) {
								LOG.warn("Error while removing fulltext entry: " + e.getMessage(), e);
							}
					    } else {
						    try {
						        if (val == null)
						            dbWords.put(ref, os.data());
						        else
						            dbWords
						            .update(val.getAddress(), ref, os
						                    .data());
						    } catch (ReadOnlyException e) {
						    }
					    }
					} catch (LockException e) {
					    LOG.warn("could not acquire lock", e);
                    } finally {
					    lock.release();
					}
				}
				words[k].clear();
			}
		}

		public void reindex(DocumentImpl oldDoc, NodeImpl node) {
		    final short collectionId = oldDoc.getCollection().getId();
		    int len, rawSize, docId;
		    Map.Entry entry;
		    String word;
		    TermFrequencyList idList;
		    TermFrequencyList.TermFreq[] ids;
		    long last, gid, delta;
		    int freq = 1;
		    byte section;
//		    NodeProxy p;
		    WordRef ref;
		    VariableByteInput is = null;
		    Lock lock = dbWords.getLock();
		    for (int k = 0; k < 2; k++) {
		        for (Iterator i = words[k].entrySet().iterator(); i.hasNext();) {
		            entry = (Map.Entry) i.next();
		            word = (String) entry.getKey();
		            idList = (TermFrequencyList) entry.getValue();
		            ref = new WordRef(collectionId, word);
		            try {
		                lock.acquire(Lock.WRITE_LOCK);
		                is = dbWords.getAsStream(ref);
		                os.clear();
		                if (is != null) {
		                    // add old entries to the new list
		                    try {
		                        while (is.available() > 0) {
		                            docId = is.readInt();
		                            section = is.readByte();
		                            len = is.readInt();
		                            rawSize = is.readFixedInt();
		                            if (docId != oldDoc.getDocId() || section != k) {
		                                // section belongs to another document:
		                                // copy data to new buffer
		                                os.writeInt(docId);
		                                os.writeByte(section);
		                                os.writeInt(len);
		                                os.writeFixedInt(rawSize);
		                                is.copyRaw(os, rawSize);
		                            } else {
		                                // copy nodes to new list
		                                gid = 0;
		                                for (int j = 0; j < len; j++) {
		                                    gid += is.readLong();
		                                    if(termFreq)
		                                    	freq = is.readInt();
		                                    if (node == null
		                                            && oldDoc.getTreeLevel(gid) < oldDoc
		                                            .reindexRequired()) {
		                                        idList.add(gid);
		                                        idList.setLastTermFreq(freq);
		                                    } else if (node != null
		                                            && (!XMLUtil
		                                                    .isDescendantOrSelf(
		                                                            oldDoc,
		                                                            node.getGID(),
		                                                            gid))) {
		                                        idList.add(gid);
		                                        idList.setLastTermFreq(freq);
		                                    }
		                                }
		                            }
		                        }
		                    } catch (EOFException e) {
		                        //LOG.error("end-of-file while reading index entry
		                        // for " + word, e);
		                    } catch (IOException e) {
		                        LOG.error("io-error while reading index entry for "
		                                + word, e);
		                    }
		                }
		                ids = idList.toArray();
		                Arrays.sort(ids);
		                len = ids.length;
		                os.writeInt(oldDoc.getDocId());
		                os.writeByte(k == 0 ? TEXT_SECTION : ATTRIBUTE_SECTION);
		                os.writeInt(len);
		                rawSize = os.position();
	                    os.writeFixedInt(0);
		                last = 0;
		                for (int j = 0; j < len; j++) {
		                    delta = ids[j].l - last;
		                    if (delta < 0) {
		                        LOG.debug("neg. delta: " + delta + " for " + word);
		                        LOG.debug("id = " + ids[j] + "; prev = " + last);
		                    }
		                    os.writeLong(delta);
		                    if(termFreq)
		                    	os.writeInt(ids[j].count);
		                    last = ids[j].l;
		                }
		                
		                os.writeFixedInt(rawSize, os.position() - rawSize - 4);
		                
		                try {
		                    if (is == null)
		                        dbWords.put(ref, os.data());
		                    else {
		                        dbWords.update(((BFile.PageInputStream) is)
		                                .getAddress(), ref, os.data());
		                    }
		                } catch (ReadOnlyException e) {
		                }
		            } catch (LockException e) {
		                LOG.error("could not acquire lock on index for '"
		                        + word + "'");
		                is = null;
		            } catch (IOException e) {
		                LOG.error("io error while reindexing word '" + word
		                        + "'");
		                is = null;
		            } finally {
		                lock.release(Lock.WRITE_LOCK);
		            }
		        }
		        words[k].clear();
		    }
		}

		public void flush() {
			final int wordsCount = words[0].size() + words[1].size();
			if (doc == null || wordsCount == 0)
				return;
			final ProgressIndicator progress = new ProgressIndicator(
					wordsCount, 100);
			final short collectionId = doc.getCollection().getId();
			int count = 1, len, lenOffset;
			Map.Entry entry;
			String word;
			TermFrequencyList idList;
			TermFrequencyList.TermFreq[] ids;
			long prevId;
			long delta;
			for (int k = 0; k < 2; k++) {
				for (Iterator i = words[k].entrySet().iterator(); i.hasNext(); count++) {
					entry = (Map.Entry) i.next();
					word = (String) entry.getKey();
					idList = (TermFrequencyList) entry.getValue();
					os.clear();
					len = idList.getSize();
					os.writeInt(doc.getDocId());
					os.writeByte(k == 0 ? TEXT_SECTION : ATTRIBUTE_SECTION);
					os.writeInt(len);
					lenOffset = os.position();
					os.writeFixedInt(0);
					prevId = 0;
					ids = idList.toArray();
					Arrays.sort(ids);
					for (int m = 0; m < len; m++) {
						delta = ids[m].l - prevId;
						if (delta < 0) {
							LOG.debug("neg. delta: " + delta + " for " + word);
							LOG.debug("id = " + ids[m] + "; prev = " + prevId);
						}
						os.writeLong(delta);
						if(termFreq)
							os.writeInt(ids[m].count);
						prevId = ids[m].l;
					}
					os.writeFixedInt(lenOffset, os.position() - lenOffset - 4);
					
					flushWord(collectionId, word, os.data());
					progress.setValue(count);
					if (progress.changed()) {
						setChanged();
						notifyObservers(progress);
					}
				}
				if (wordsCount > 100) {
					progress.finish();
					setChanged();
					notifyObservers(progress);
				}
				words[k].clear();
			}
//			dbWords.debugFreeList();
		}

		private void flushWord(short collectionId, String word, ByteArray data) {
			if (data.size() == 0)
				return;
			Lock lock = dbWords.getLock();
			try {
				lock.acquire(Lock.WRITE_LOCK);
				try {
					dbWords.append(new WordRef(collectionId, word), data);
				} catch (ReadOnlyException e) {
				} catch (IOException ioe) {
					LOG.warn("io error while writing '" + word + "'", ioe);
				}
			} catch (LockException e) {
				LOG.warn("could not acquire lock", e);
			} finally {
				lock.release();
			}
		}

		public void setDocument(DocumentImpl doc) {
			if (this.doc != null && this.doc.getDocId() != doc.getDocId())
				flush();
			this.doc = doc;
		}
	}
	
	private class IndexCallback implements BTreeCallback {
		
		List matches = new ArrayList();
		TermMatcher matcher;
		XQueryContext context;
		
		public IndexCallback(XQueryContext context, TermMatcher matcher) {
			this.matcher = matcher;
			this.context = context;
		}
		
		public String[] getMatches() {
			String[] a = new String[matches.size()];
			return (String[]) matches.toArray(a);
		}
		
		/* (non-Javadoc)
		 * @see org.dbxml.core.filer.BTreeCallback#indexInfo(org.dbxml.core.data.Value, long)
		 */
		public boolean indexInfo(Value key, long pointer) throws TerminatedException {
		    if(context != null)
		        context.proceed();
			String word;
			try {
				word = new String(key.getData(), 2, key.getLength() - 2,
						"UTF-8");
			} catch (UnsupportedEncodingException uee) {
				word = new String(key.getData(), 2, key.getLength() - 2);
			}
			if (matcher.matches(word))
				matches.add(word);
			return true;
		}
	}
	
	private final class SearchCallback implements BTreeCallback {

		DocumentSet docs;
		TermMatcher matcher;
		NodeSet result;
		NodeSet contextSet;
		XQueryContext context;
		XMLString word = new XMLString(64);
        
		public SearchCallback(XQueryContext context, TermMatcher comparator, NodeSet result,
				NodeSet contextSet, DocumentSet docs) {
			this.matcher = comparator;
			this.result = result;
			this.docs = docs;
			this.contextSet = contextSet;
			this.context = context;
		}

		public boolean indexInfo(Value key, long pointer) throws TerminatedException {
            word.reuse();
            word = UTF8.decode(key.getData(), 2, key.getLength() - 2, word);
			if (matcher.matches(word)) {
				VariableByteInput is = null;
				try {
					is = dbWords.getAsStream(pointer);
				} catch (IOException ioe) {
					LOG.warn(ioe.getMessage(), ioe);
				}
				if (is == null)
					return true;
//				int k = 0;
				int docId;
				int len, rawSize;
				long gid;
				long last = -1;
				int freq = 1;
				int sizeHint = -1;
				byte section;
				DocumentImpl doc;
				NodeProxy parent, proxy;
				Match match;
				try {
					while (is.available() > 0) {
					    if(context != null)
					        context.proceed();
						docId = is.readInt();
						section = is.readByte();
						len = is.readInt();
						rawSize = is.readFixedInt();
						if ((doc = docs.getDoc(docId)) == null) {
							is.skipBytes(rawSize);
							continue;
						}
						if (contextSet != null)
							sizeHint = contextSet.getSizeHint(doc);
						last = 0;
						for (int j = 0; j < len; j++) {
							gid = last + is.readLong();
							if(termFreq)
								freq = is.readInt();
							last = gid;
							proxy = (section == TEXT_SECTION
									? new NodeProxy(doc, gid,
											Node.TEXT_NODE)
									: new NodeProxy(doc, gid,
											Node.ATTRIBUTE_NODE));
							if (contextSet != null) {
								if (section == TEXT_SECTION)
									parent = contextSet.parentWithChild(proxy, false,
										true, -1);
								else
									parent = contextSet.get(proxy);
								if (parent != null) {
									result.add(parent, sizeHint);
									match = new Match(word.toString(), gid);
									match.setFrequency(freq);
									if (trackMatches != Serializer.TAG_NONE)
										parent.addMatch(match);
								}
							} else
								result.add(proxy, sizeHint);
						}
					}
				} catch (EOFException e) {
					// EOFExceptions are normal
				} catch (IOException e) {
					LOG.warn("io error while reading index", e);
				}
			}
			if (contextSet != null)
				((ExtArrayNodeSet) result).sort();
			return true;
		}
	}
	
	private final class IndexScanCallback implements BTreeCallback{
		
		private DocumentSet docs;
		private NodeSet contextSet;
		private Map map = new TreeMap();
		
		IndexScanCallback(DocumentSet docs, NodeSet contextSet) {
			this.docs = docs;
			this.contextSet = contextSet;
		}
		
		/* (non-Javadoc)
		 * @see org.dbxml.core.filer.BTreeCallback#indexInfo(org.dbxml.core.data.Value, long)
		 */
		public boolean indexInfo(Value key, long pointer)
				throws TerminatedException {
			String term;
			try {
				term = new String(key.getData(), 2, key.getLength() - 2,
						"UTF-8");
			} catch (UnsupportedEncodingException uee) {
				term = new String(key.getData(), 2, key.getLength() - 2);
			}
			Occurrences oc = (Occurrences) map.get(term);
			
			VariableByteInput is = null;
			try {
				is = dbWords.getAsStream(pointer);
			} catch (IOException ioe) {
				LOG.warn(ioe.getMessage(), ioe);
			}
			if (is == null)
				return true;
			try {
				int docId;
				byte section;
				int len, rawSize;
				int freq = 1;
				long gid;
				DocumentImpl doc;
				boolean include = true;
				boolean docAdded;
				NodeProxy p;
				while (is.available() > 0) {
					docId = is.readInt();
					section = 
						is.readByte();
					len = is.readInt();
					rawSize = is.readFixedInt();
					if ((doc = docs.getDoc(docId)) == null) {
						is.skipBytes(rawSize);
						continue;
					}
					docAdded = false;
					gid = 0;
					for (int j = 0; j < len; j++) {
						gid += is.readLong();
						if(termFreq)
							freq = is.readInt();
						// fixme! Now checks the
						// context to see what type
						// to output, should be
						// available info according
						// to indexer settings?
						if (contextSet != null) {
							p = contextSet.parentWithChild(doc, gid, false, true);
							if (section == ATTRIBUTE_SECTION) {
								include = p.nodeType == Node.ATTRIBUTE_NODE;
							} else {
								include = p != null;
							}
							include = contextSet.parentWithChild(doc, gid, false, true) != null;
						}
						if (include) {
							if (oc == null) {
								oc = new Occurrences(term);
								map.put(term, oc);
							}
							if (!docAdded) {
								oc.addDocument(doc);
								docAdded = true;
							}
							oc.addOccurrences(freq);
						}
					}
				}
			} catch(EOFException e) {
			} catch(IOException e) {
				LOG.warn("Exception while scanning index: " + e.getMessage(), e);
			}
			return true;
		}
	}
	
	private static class TermFrequencyList {
		
		protected static class TermFreq implements Comparable {
			
			long l;
			int count = 1;
			TermFreq next = null;
			
			public TermFreq(long l) {
				this.l = l;
			}
			
			public void increment() {
				++count;
			}
			
			public int compareTo(Object o) {
				final TermFreq other = (TermFreq)o;
				if(l == other.l)
					return 0;
				else
					return l > other.l ? 1 : -1;
			}
		}
		
		private TermFreq first = null;
		private TermFreq last = null;
		private int count = 0;
		
		public void add( long l ) {
			if(first == null) {
				first = new TermFreq( l );
				last = first;
			} else {
				TermFreq next = new TermFreq( l );
				last.next = next;
				last = next;
			}
			++count;
		}
		
		public void incLastTerm() {
			if(last != null)
				((TermFreq)last).increment();
		}
		
		public void setLastTermFreq(int freq) {
			if(last != null)
				((TermFreq)last).count = freq;
		}
		
		public long getLast() {
	    	if(last != null)
	    		return last.l;
	    	else
	    		return -1;
	    }
		
		public boolean contains(long l) {
	    	TermFreq next = first;
	    	while( next != null ) {
	    		if(next.l == l)
	    			return true;
	    		next = next.next;
	    	}
	    	return false;
	    }
		
		public int getSize() {
			return count;
		}
		
		public TermFreq[] toArray() {
			TermFreq[] data = new TermFreq[count];
			TermFreq next = first;
			int i = 0;
			while( next != null ) {
				data[i++] = (TermFreq)next;
				next = next.next;
			}
			return data;
		}
	}

	private final static class WordRef extends Value {

		public WordRef(short collectionId) {
			data = new byte[2];
			ByteConversion.shortToByte(collectionId, data, 0);
			len = 2;
		}

		public WordRef(short collectionId, String word) {
			len = UTF8.encoded(word) + 2;
			data = new byte[len];
			ByteConversion.shortToByte(collectionId, data, 0);
			UTF8.encode(word, data, 2);
		}

		/**
		 * @see java.lang.Object#toString()
		 */
		public String toString() {
			return ByteConversion.byteToShort(data, pos)
					+ new String(data, pos, len);
		}
	}

	public void storeAttribute(RangeIndexSpec spec, AttrImpl node) {
		// TODO Auto-generated method stu	
	}

	public void setDocument(DocumentImpl document) {
	}

	public void storeAttribute(AttrImpl node, NodePath currentPath, boolean fullTextIndexSwitch) {
		// TODO Auto-generated method stub
		
	}

	public void storeText(TextImpl node, NodePath currentPath, boolean fullTextIndexSwitch) {
		// TODO Auto-generated method stub
		
	}

	public void startElement(ElementImpl impl, NodePath currentPath, boolean index) {
		// TODO Auto-generated method stub
		
	}

	public void endElement(int xpathType, ElementImpl node, String content) {
		// TODO Auto-generated method stub
		
	}

	public void removeElement(ElementImpl node, NodePath currentPath, String content) {
		// TODO Auto-generated method stub
		
	}
}
