package org.trippi.impl.sesame;

import java.net.URI;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.jrdf.graph.BlankNode;
import org.jrdf.graph.GraphElementFactory;
import org.jrdf.graph.Literal;
import org.jrdf.graph.Node;
import org.jrdf.graph.ObjectNode;
import org.jrdf.graph.PredicateNode;
import org.jrdf.graph.SubjectNode;
import org.jrdf.graph.Triple;
import org.jrdf.graph.URIReference;
import org.openrdf.model.ValueFactory;
import org.openrdf.query.QueryLanguage;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryException;
import org.trippi.RDFUtil;
import org.trippi.TripleIterator;
import org.trippi.TrippiException;
import org.trippi.TupleIterator;
import org.trippi.AliasManager;
import org.trippi.impl.base.TriplestoreSession;

/**
 * A <code>TriplestoreSession</code> that wraps a SesameRepository.
 *
 * @author cwilper@cs.cornell.edu
 */
public class SesameSession implements TriplestoreSession {
    public static final Map<String, QueryLanguage> languageMap = new HashMap<String, QueryLanguage>();
    static {
    	for (QueryLanguage i: QueryLanguage.values()) {
    		languageMap.put(i.getName().toLowerCase(), i);
    	}
    }

    public static final String[] TUPLE_LANGUAGES = (String[]) languageMap.keySet().toArray();
    public static final String[] TRIPLE_LANGUAGES = (String[]) languageMap.keySet().toArray();

    private static final Logger logger = Logger.getLogger(SesameSession.class.getName());

    private Repository m_repository;
    private AliasManager m_aliasManager;

    private GraphElementFactory m_factory;

    private boolean m_closed;

    public String[] listTupleLanguages() {
        return TUPLE_LANGUAGES;
    }

    public String[] listTripleLanguages() {
        return TRIPLE_LANGUAGES;
    }

    /**
     * Construct a Sesame session.
     */
    public SesameSession(Repository repository,
                         AliasManager aliasManager) {
        m_repository = repository;
        m_aliasManager = aliasManager;
        m_closed = false;
        logger.info("Created session.");
    }

    public GraphElementFactory getElementFactory() {
        if (m_factory == null) {
            m_factory = new RDFUtil();
        }
        return m_factory;
    }


    public void add(Set<Triple> triples) throws TrippiException {
        doTriples(triples, true);
    }

    public void delete(Set<Triple> triples) throws TrippiException {
        doTriples(triples, false);
    }

    private void doTriples(Set<Triple> triples, 
                           boolean add) throws TrippiException {
        try {
            if (add) {
            	m_repository.getConnection().add(getSesameGraph(triples.iterator()));
            } else {
                m_repository.getConnection().remove(getSesameGraph(triples.iterator()));
            }
        } catch (Exception e) {
            e.printStackTrace();
            String mod = "deleting";
            if (add) mod = "adding";
            String msg = "Error " + mod + " triples: " + e.getClass().getName();
            if (e.getMessage() != null) msg = msg + ": " + e.getMessage();
            throw new TrippiException(msg, e);
        }
    }

    private String doAliasReplacements(String q, boolean noBrackets) {
        String out = q;
        Map<String, String> m = m_aliasManager.getAliasMap();
        Iterator<String> iter = m.keySet().iterator();
        while (iter.hasNext()) {
            String alias = iter.next();
            String fullForm = m.get(alias);
            if (noBrackets) {
                // In serql and rql, aliases are not surrounded by < and >
                // If bob is an alias for http://example.org/robert/,
                // this turns bob:fun into <http://example.org/robert/fun>,
                // {bob:fun} into {<http://example.org/robert/fun>},
                // and "10"^^xsd:int into "10"^^<http://www.w3.org/2001/XMLSchema#int>
                out = out.replaceAll("([\\s{\\^])" + alias + ":([^\\s}]+)",
                                     "$1<" + fullForm + "$2>");
            } else {
                // In other query languages, aliases are surrounded by < and >
                // If bob is an alias for http://example.org/robert/,
                // this turns <bob:fun> into <http://example.org/robert/fun>
                // and "10"^^xsd:int into "10"^^<http://www.w3.org/2001/XMLSchema#int>
                out = out.replaceAll("<" + alias + ":", "<" + fullForm)
                         .replaceAll("\\^\\^" + alias + ":(\\S+)", "^^<" + fullForm + "$1>");
            }
        }
        if (!q.equals(out)) {
            logger.info("Substituted aliases, query is now: " + out);
        }
        return out;
    }

    public TripleIterator findTriples(SubjectNode subject,
                                      PredicateNode predicate,
                                      ObjectNode object) throws TrippiException {
        // convert the pattern to a SERQL CONSTRUCT query and run that
        StringBuffer buf = new StringBuffer();
        buf.append("CONSTRUCT * FROM {S} " + getString("P", predicate) + " {O}");

        if (subject != null || object != null) {
            buf.append(" WHERE ");
            if (subject != null) {
                buf.append("S = " + getString(null, subject));
                if (object != null) buf.append(" AND ");
            }
            if (object != null) {
                buf.append("O = " + getString(null, object));
            }
        }

        return findTriples(QueryLanguage.SERQL.getName().toLowerCase(), buf.toString());
    }

    private String getString(String ifNull, Node rdfNode) {
        if (rdfNode == null) return ifNull;
        return RDFUtil.toString(rdfNode);
    }

    public TripleIterator findTriples(String lang,
                                      String queryText) throws TrippiException {
        if (QueryLanguage.SERQL.getName().equalsIgnoreCase(lang)) {
            // this is expected to be a SERQL CONSTRUCT query
            return new SesameTripleIterator(QueryLanguage.SERQL, 
                                            doAliasReplacements(queryText, true), 
                                            m_repository);
        } else {
            throw new TrippiException("Unsupported triple query language: " + lang);
        }
    }

    public TupleIterator query(String queryText,
                               String language) throws TrippiException {
        QueryLanguage lang = languageMap.get(language.toLowerCase());
        if (lang == null) {
            throw new TrippiException("Unsupported query language: " + language);
        }
        
        queryText = doAliasReplacements(queryText, lang == QueryLanguage.SERQL || lang == QueryLanguage.SPARQL);

        return new SesameTupleIterator(lang, queryText, m_repository);
    }

    public void close() throws TrippiException {
        if ( !m_closed ) {
            try {
				m_repository.shutDown();
			} catch (RepositoryException e) {
				throw new TrippiException("Exception in close().", e);
			}
            m_closed = true;
            logger.info("Closed session.");
        }
    }

    /**
     * Ensure close() gets called at garbage collection time.
     */
    public void finalize() throws TrippiException {
        close();
    }

    //////////////////////////////////////////////////////////////////////////
    //////// Utility methods for converting JRDF types to Sesame types ///////
    //////////////////////////////////////////////////////////////////////////

    public static org.openrdf.model.Graph getSesameGraph(Iterator<Triple> jrdfTriples) {
        org.openrdf.model.Graph graph = new org.openrdf.model.impl.GraphImpl();
        while (jrdfTriples.hasNext()) {
            Triple triple = jrdfTriples.next();
            graph.add(getSesameStatement(triple, graph.getValueFactory()));
        }
        return graph;
    }

    public static org.openrdf.model.Statement getSesameStatement(
            Triple triple, 
            ValueFactory valueFactory) {
        return new org.openrdf.model.impl.StatementImpl(
                    getSesameResource(triple.getSubject(), valueFactory),
                    getSesameURI((URIReference) triple.getPredicate(), valueFactory),
                    getSesameValue(triple.getObject(), valueFactory));
    }

    public static org.openrdf.model.Resource getSesameResource(
            SubjectNode subject,
            ValueFactory valueFactory) {
        if (subject instanceof BlankNode) {
            return valueFactory.createBNode("" + subject.hashCode());
        } else {  // must be a URIReference
            return getSesameURI((URIReference) subject, valueFactory);
        }
    }

    public static org.openrdf.model.URI getSesameURI(
            URIReference uriReference,
            ValueFactory valueFactory) {
        return valueFactory.createURI(uriReference.getURI().toString());
    }

    public static org.openrdf.model.Value getSesameValue(
            ObjectNode object,
            ValueFactory valueFactory) {
        if (object instanceof BlankNode) {
            return valueFactory.createBNode("" + object.hashCode());
        } else if (object instanceof URIReference) {
            return getSesameURI((URIReference) object, valueFactory);
        } else { // must be a Literal
            return getSesameLiteral((Literal) object, valueFactory);
        }
    }

    public static org.openrdf.model.Literal getSesameLiteral(
            Literal literal,
            ValueFactory valueFactory) {
        String value = literal.getLexicalForm();
        String lang  = literal.getLanguage();
        URI    type  = literal.getDatatypeURI(); 
        if (lang != null) {
            return valueFactory.createLiteral(value, lang);
        } else if (type != null) {
            return valueFactory.createLiteral(value, 
                                              valueFactory.createURI(
                                                           type.toString()));
        } else {
            return valueFactory.createLiteral(value);
        }
    }

}
