package edu.uci.ics.asterix.translator;

import java.io.FileReader;
import java.io.Reader;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import edu.uci.ics.asterix.aql.base.Clause;
import edu.uci.ics.asterix.aql.base.Expression;
import edu.uci.ics.asterix.aql.base.Statement;
import edu.uci.ics.asterix.aql.base.Statement.Kind;
import edu.uci.ics.asterix.aql.expression.BeginFeedStatement;
import edu.uci.ics.asterix.aql.expression.CallExpr;
import edu.uci.ics.asterix.aql.expression.ControlFeedStatement;
import edu.uci.ics.asterix.aql.expression.ControlFeedStatement.OperationType;
import edu.uci.ics.asterix.aql.expression.CreateIndexStatement;
import edu.uci.ics.asterix.aql.expression.DeleteStatement;
import edu.uci.ics.asterix.aql.expression.FLWOGRExpression;
import edu.uci.ics.asterix.aql.expression.FieldAccessor;
import edu.uci.ics.asterix.aql.expression.FieldBinding;
import edu.uci.ics.asterix.aql.expression.ForClause;
import edu.uci.ics.asterix.aql.expression.FunIdentifier;
import edu.uci.ics.asterix.aql.expression.Identifier;
import edu.uci.ics.asterix.aql.expression.InsertStatement;
import edu.uci.ics.asterix.aql.expression.LiteralExpr;
import edu.uci.ics.asterix.aql.expression.LoadFromFileStatement;
import edu.uci.ics.asterix.aql.expression.Query;
import edu.uci.ics.asterix.aql.expression.RecordConstructor;
import edu.uci.ics.asterix.aql.expression.VariableExpr;
import edu.uci.ics.asterix.aql.expression.WhereClause;
import edu.uci.ics.asterix.aql.expression.WriteFromQueryResultStatement;
import edu.uci.ics.asterix.aql.literal.StringLiteral;
import edu.uci.ics.asterix.aql.parser.AQLParser;
import edu.uci.ics.asterix.common.config.DatasetConfig.DatasetType;
import edu.uci.ics.asterix.common.config.DatasetConfig.IndexType;
import edu.uci.ics.asterix.metadata.IDatasetDetails;
import edu.uci.ics.asterix.metadata.MetadataException;
import edu.uci.ics.asterix.metadata.MetadataManager;
import edu.uci.ics.asterix.metadata.MetadataTransactionContext;
import edu.uci.ics.asterix.metadata.declared.AqlCompiledDatasetDecl;
import edu.uci.ics.asterix.metadata.declared.AqlCompiledMetadataDeclarations;
import edu.uci.ics.asterix.metadata.entities.Dataset;
import edu.uci.ics.asterix.metadata.entities.FeedDatasetDetails;
import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.asterix.transaction.management.exception.ACIDException;
import edu.uci.ics.asterix.transaction.management.service.transaction.TransactionManagementConstants.LockManagerConstants.LockMode;
import edu.uci.ics.hyracks.algebricks.core.api.exceptions.AlgebricksException;

public class DmlTranslator extends AbstractAqlTranslator {

    private final MetadataTransactionContext mdTxnCtx;
    private final List<Statement> aqlStatements;
    private AqlCompiledMetadataDeclarations compiledDeclarations;
    private List<ICompiledDmlStatement> compiledDmlStatements;

    public DmlTranslator(MetadataTransactionContext mdTxnCtx, List<Statement> aqlStatements) {
        this.mdTxnCtx = mdTxnCtx;
        this.aqlStatements = aqlStatements;
    }

    public void translate() throws AlgebricksException, RemoteException, ACIDException, MetadataException {
        compiledDeclarations = compileMetadata(mdTxnCtx, aqlStatements, true);
        compiledDmlStatements = compileDmlStatements();
    }

    public AqlCompiledMetadataDeclarations getCompiledDeclarations() {
        return compiledDeclarations;
    }

    public List<ICompiledDmlStatement> getCompiledDmlStatements() {
        return compiledDmlStatements;
    }

    private List<ICompiledDmlStatement> compileDmlStatements() throws AlgebricksException {
        List<ICompiledDmlStatement> dmlStatements = new ArrayList<ICompiledDmlStatement>();
        for (Statement stmt : aqlStatements) {
            validateOperation(compiledDeclarations, stmt);
            switch (stmt.getKind()) {
                case LOAD_FROM_FILE: {
                    LoadFromFileStatement st1 = (LoadFromFileStatement) stmt;
                    CompiledLoadFromFileStatement cls = new CompiledLoadFromFileStatement(st1.getDatasetName()
                            .getValue(), st1.getAdapter(), st1.getProperties(), st1.dataIsAlreadySorted());
                    dmlStatements.add(cls);
                    break;
                }
                case WRITE_FROM_QUERY_RESULT: {
                    WriteFromQueryResultStatement st1 = (WriteFromQueryResultStatement) stmt;
                    CompiledWriteFromQueryResultStatement clfrqs = new CompiledWriteFromQueryResultStatement(st1
                            .getDatasetName().getValue(), st1.getQuery(), st1.getVarCounter());
                    dmlStatements.add(clfrqs);
                    break;
                }
                case CREATE_INDEX: {
                    CreateIndexStatement cis = (CreateIndexStatement) stmt;
                    if (cis.getNeedToCreate()) {
                        CompiledCreateIndexStatement ccis = new CompiledCreateIndexStatement(cis.getIndexName()
                                .getValue(), cis.getDatasetName().getValue(), cis.getFieldExprs(), cis.getIndexType());
                        dmlStatements.add(ccis);
                    }
                    break;
                }
                case INSERT: {
                    InsertStatement is = (InsertStatement) stmt;
                    CompiledInsertStatement clfrqs = new CompiledInsertStatement(is.getDatasetName().getValue(),
                            is.getQuery(), is.getVarCounter());
                    dmlStatements.add(clfrqs);
                    break;
                }
                case DELETE: {
                    DeleteStatement ds = (DeleteStatement) stmt;
                    CompiledDeleteStatement clfrqs = new CompiledDeleteStatement(ds.getVariableExpr(),
                            ds.getDatasetName(), ds.getCondition(), ds.getDieClause(), ds.getVarCounter(),
                            compiledDeclarations);
                    dmlStatements.add(clfrqs);
                    break;
                }

                case BEGIN_FEED: {
                    BeginFeedStatement bfs = (BeginFeedStatement) stmt;
                    CompiledBeginFeedStatement cbfs = new CompiledBeginFeedStatement(bfs.getDatasetName(),
                            bfs.getQuery(), bfs.getVarCounter());
                    dmlStatements.add(cbfs);
                    Dataset dataset;
                    try {
                        dataset = MetadataManager.INSTANCE.getDataset(mdTxnCtx,
                                compiledDeclarations.getDataverseName(), bfs.getDatasetName().getValue());
                    } catch (MetadataException me) {
                        throw new AlgebricksException(me);
                    }
                    IDatasetDetails datasetDetails = dataset.getDatasetDetails();
                    if (datasetDetails.getDatasetType() != DatasetType.FEED) {
                        throw new IllegalArgumentException("Dataset " + bfs.getDatasetName().getValue()
                                + " is not a feed dataset");
                    }
                    bfs.initialize((FeedDatasetDetails) datasetDetails);
                    cbfs.setQuery(bfs.getQuery());
                    break;
                }

                case CONTROL_FEED: {
                    ControlFeedStatement cfs = (ControlFeedStatement) stmt;
                    CompiledControlFeedStatement clcfs = new CompiledControlFeedStatement(cfs.getOperationType(),
                            cfs.getDatasetName(), cfs.getAlterAdapterConfParams());
                    dmlStatements.add(clcfs);
                    break;

                }
            }
        }
        return dmlStatements;
    }

    public static interface ICompiledDmlStatement {

        public abstract Kind getKind();
    }

    public static class CompiledCreateIndexStatement implements ICompiledDmlStatement {
        private String indexName;
        private String datasetName;
        private List<String> keyFields;
        private IndexType indexType;

        public CompiledCreateIndexStatement(String indexName, String datasetName, List<String> keyFields,
                IndexType indexType) {
            this.indexName = indexName;
            this.datasetName = datasetName;
            this.keyFields = keyFields;
            this.indexType = indexType;
        }

        public String getDatasetName() {
            return datasetName;
        }

        public String getIndexName() {
            return indexName;
        }

        public List<String> getKeyFields() {
            return keyFields;
        }

        public IndexType getIndexType() {
            return indexType;
        }

        @Override
        public Kind getKind() {
            return Kind.CREATE_INDEX;
        }
    }

    public static class CompiledLoadFromFileStatement implements ICompiledDmlStatement {
        private String datasetName;
        private boolean alreadySorted;
        private String adapter;
        private Map<String, String> properties;

        public CompiledLoadFromFileStatement(String datasetName, String adapter, Map<String, String> properties,
                boolean alreadySorted) {
            this.datasetName = datasetName;
            this.alreadySorted = alreadySorted;
            this.adapter = adapter;
            this.properties = properties;
        }

        public String getDatasetName() {
            return datasetName;
        }

        public boolean alreadySorted() {
            return alreadySorted;
        }

        public String getAdapter() {
            return adapter;
        }

        public Map<String, String> getProperties() {
            return properties;
        }

        @Override
        public Kind getKind() {
            return Kind.LOAD_FROM_FILE;
        }
    }

    public static class CompiledWriteFromQueryResultStatement implements ICompiledDmlStatement {

        private String datasetName;
        private Query query;
        private int varCounter;

        public CompiledWriteFromQueryResultStatement(String datasetName, Query query, int varCounter) {
            this.datasetName = datasetName;
            this.query = query;
            this.varCounter = varCounter;
        }

        public String getDatasetName() {
            return datasetName;
        }

        public int getVarCounter() {
            return varCounter;
        }

        public Query getQuery() {
            return query;
        }

        @Override
        public Kind getKind() {
            return Kind.WRITE_FROM_QUERY_RESULT;
        }

    }

    public static class CompiledInsertStatement implements ICompiledDmlStatement {
        private String datasetName;
        private Query query;
        private int varCounter;

        public CompiledInsertStatement(String datasetName, Query query, int varCounter) {
            this.datasetName = datasetName;
            this.query = query;
            this.varCounter = varCounter;
        }

        public String getDatasetName() {
            return datasetName;
        }

        public int getVarCounter() {
            return varCounter;
        }

        public Query getQuery() {
            return query;
        }

        @Override
        public Kind getKind() {
            return Kind.INSERT;
        }
    }

    public static class CompiledBeginFeedStatement implements ICompiledDmlStatement {
        private Identifier datasetName;
        private Query query;
        private int varCounter;

        public CompiledBeginFeedStatement(Identifier datasetName, Query query, int varCounter) {
            this.datasetName = datasetName;
            this.query = query;
            this.varCounter = varCounter;
        }

        public Identifier getDatasetName() {
            return datasetName;
        }

        public int getVarCounter() {
            return varCounter;
        }

        public Query getQuery() {
            return query;
        }

        public void setQuery(Query query) {
            this.query = query;
        }

        @Override
        public Kind getKind() {
            return Kind.BEGIN_FEED;
        }
    }

    public static class CompiledControlFeedStatement implements ICompiledDmlStatement {
        private Identifier datasetName;
        private OperationType operationType;
        private Query query;
        private int varCounter;
        private Map<String, String> alteredParams;

        public CompiledControlFeedStatement(OperationType operationType, Identifier datasetName,
                Map<String, String> alteredParams) {
            this.datasetName = datasetName;
            this.operationType = operationType;
            this.alteredParams = alteredParams;
        }

        public Identifier getDatasetName() {
            return datasetName;
        }

        public OperationType getOperationType() {
            return operationType;
        }

        public int getVarCounter() {
            return varCounter;
        }

        public Query getQuery() {
            return query;
        }

        @Override
        public Kind getKind() {
            return Kind.CONTROL_FEED;
        }

        public Map<String, String> getProperties() {
            return alteredParams;
        }

        public void setProperties(Map<String, String> properties) {
            this.alteredParams = properties;
        }
    }

    public static class CompiledDeleteStatement implements ICompiledDmlStatement {
        private VariableExpr var;
        private Identifier dataset;
        private Expression condition;
        private Clause dieClause;
        private int varCounter;
        private AqlCompiledMetadataDeclarations compiledDeclarations;

        public CompiledDeleteStatement(VariableExpr var, Identifier dataset, Expression condition, Clause dieClause,
                int varCounter, AqlCompiledMetadataDeclarations compiledDeclarations) {
            this.var = var;
            this.dataset = dataset;
            this.condition = condition;
            this.dieClause = dieClause;
            this.varCounter = varCounter;
            this.compiledDeclarations = compiledDeclarations;
        }

        public Identifier getDataset() {
            return dataset;
        }

        public String getDatasetName() {
            return dataset.getValue();
        }

        public int getVarCounter() {
            return varCounter;
        }

        public Expression getCondition() {
            return condition;
        }

        public Clause getDieClause() {
            return dieClause;
        }

        public Query getQuery() throws AlgebricksException {
            String datasetName = dataset.getValue();

            List<Expression> arguments = new ArrayList<Expression>();
            LiteralExpr argumentLiteral = new LiteralExpr(new StringLiteral(datasetName));
            arguments.add(argumentLiteral);

            CallExpr callExpression = new CallExpr(new FunIdentifier("dataset", 1), arguments);
            List<Clause> clauseList = new ArrayList<Clause>();
            Clause forClause = new ForClause(var, callExpression);
            clauseList.add(forClause);
            Clause whereClause = null;
            if (condition != null) {
                whereClause = new WhereClause(condition);
                clauseList.add(whereClause);
            }
            if (dieClause != null) {
                clauseList.add(dieClause);
            }

            AqlCompiledDatasetDecl aqlDataset = compiledDeclarations.findDataset(datasetName);
            if (aqlDataset == null) {
                throw new AlgebricksException("Unknown dataset " + datasetName);
            }
            String itemTypeName = aqlDataset.getItemTypeName();
            IAType itemType = compiledDeclarations.findType(itemTypeName);
            ARecordType recType = (ARecordType) itemType;
            String[] fieldNames = recType.getFieldNames();
            List<FieldBinding> fieldBindings = new ArrayList<FieldBinding>();
            for (int i = 0; i < fieldNames.length; i++) {
                FieldAccessor fa = new FieldAccessor(var, new Identifier(fieldNames[i]));
                FieldBinding fb = new FieldBinding(new LiteralExpr(new StringLiteral(fieldNames[i])), fa);
                fieldBindings.add(fb);
            }
            RecordConstructor rc = new RecordConstructor(fieldBindings);

            FLWOGRExpression flowgr = new FLWOGRExpression(clauseList, rc);
            Query query = new Query();
            query.setBody(flowgr);
            return query;
        }

        @Override
        public Kind getKind() {
            return Kind.DELETE;
        }

    }

    public static void main(String args[]) throws Exception {
        Reader reader = new FileReader(args[0]);
        AQLParser parser = new AQLParser(reader);
        Query q = (Query) parser.Statement();

        // Begin a transaction against the metadata.
        // Lock the metadata in X mode to protect against other DDL and DML.
        // TODO: Is there a way to know whether we can get away with locking in
        // S mode?
        MetadataTransactionContext ctx = MetadataManager.INSTANCE.beginTransaction();
        MetadataManager.INSTANCE.lock(ctx, LockMode.EXCLUSIVE);
        try {
            DmlTranslator dmlt = new DmlTranslator(ctx, q.getPrologDeclList());
            dmlt.translate();
            // dmlt.execute();
            MetadataManager.INSTANCE.commitTransaction(ctx);
        } catch (Exception e) {
            MetadataManager.INSTANCE.abortTransaction(ctx);
            throw e;
        }
    }
}
