/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */
package com.akiban.sql.aisddl;

import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.ais.model.AISBuilder;
import com.akiban.ais.model.Parameter;
import com.akiban.ais.model.Routine;
import com.akiban.ais.model.SQLJJar;
import com.akiban.ais.model.TableName;
import com.akiban.ais.model.Type;
import com.akiban.qp.operator.QueryContext;
import com.akiban.server.api.DDLFunctions;
import com.akiban.server.error.InvalidRoutineException;
import com.akiban.server.error.NoSuchRoutineException;
import com.akiban.server.error.NoSuchSQLJJarException;
import com.akiban.server.service.routines.RoutineLoader;
import com.akiban.server.service.session.Session;
import com.akiban.sql.parser.CreateAliasNode;
import com.akiban.sql.parser.DropAliasNode;

import com.akiban.sql.types.DataTypeDescriptor;
import com.akiban.sql.types.RoutineAliasInfo;
import java.sql.ParameterMetaData;

public class RoutineDDL {
    private RoutineDDL() { }
    
    static class ParameterStyleCallingConvention {
        final String language, parameterStyle;
        final Routine.CallingConvention callingConvention;
        
        ParameterStyleCallingConvention(String language, String parameterStyle,
                                        Routine.CallingConvention callingConvention) {
            this.language = language;
            this.parameterStyle = parameterStyle;
            this.callingConvention = callingConvention;
        }
    }

    static final ParameterStyleCallingConvention[] parameterStyleCallingConventions = {
        new ParameterStyleCallingConvention("JAVA", "JAVA", 
                                            Routine.CallingConvention.JAVA),
        new ParameterStyleCallingConvention("JAVA", "AKIBAN_LOADABLE_PLAN", 
                                            Routine.CallingConvention.LOADABLE_PLAN),
        new ParameterStyleCallingConvention("SQL", "ROW", 
                                            Routine.CallingConvention.SQL_ROW),
        new ParameterStyleCallingConvention(null, "VARIABLES", 
                                            Routine.CallingConvention.SCRIPT_BINDINGS),
        new ParameterStyleCallingConvention(null, "JAVA", 
                                            Routine.CallingConvention.SCRIPT_FUNCTION_JAVA)
    };

    protected static Routine.CallingConvention findCallingConvention(String schemaName,
                                                                     String routineName,
                                                                     String language,
                                                                     String parameterStyle,
                                                                     RoutineLoader routineLoader,
                                                                     Session session) {
        boolean languageSeen = false, isScript = false, scriptChecked = false;
        for (ParameterStyleCallingConvention cc : parameterStyleCallingConventions) {
            if (cc.language == null) {
                if (!scriptChecked) {
                    isScript = routineLoader.isScriptLanguage(session, language);
                    scriptChecked = true;
                }
                if (!isScript) continue;
            }
            else if (cc.language.equalsIgnoreCase(language)) {
                languageSeen = true;
            }
            else {
                continue;
            }
            if (cc.parameterStyle.equalsIgnoreCase(parameterStyle)) {
                return cc.callingConvention;
            }
        }
        if (languageSeen) {
            throw new InvalidRoutineException(schemaName, routineName, "unsupported PARAMETER STYLE " + parameterStyle);
        }
        else {
            throw new InvalidRoutineException(schemaName, routineName, "unsupported LANGUAGE " + language);
        }
    }

    public static void createRoutine(DDLFunctions ddlFunctions,
                                     RoutineLoader routineLoader,
                                     Session session,
                                     String defaultSchemaName,
                                     CreateAliasNode createAlias) {
        RoutineAliasInfo aliasInfo = (RoutineAliasInfo)createAlias.getAliasInfo();
        TableName tableName = DDLHelper.convertName(defaultSchemaName, createAlias.getObjectName());
        String schemaName = tableName.getSchemaName();
        String routineName = tableName.getTableName();
        String language = aliasInfo.getLanguage();
        Routine.CallingConvention callingConvention = findCallingConvention(schemaName, routineName, language, aliasInfo.getParameterStyle(),
                                                                            routineLoader, session);
        switch (callingConvention) {
        case SQL_ROW:
        case SCRIPT_BINDINGS:
            if (createAlias.getExternalName() != null)
                throw new InvalidRoutineException(schemaName, routineName, language + " routine cannot have EXTERNAL NAME");
            break;
        case SCRIPT_FUNCTION_JAVA:
            if (createAlias.getExternalName() == null) {
                throw new InvalidRoutineException(schemaName, routineName, "must have EXTERNAL NAME function_name");
            }
        }
        AISBuilder builder = new AISBuilder();
        builder.routine(schemaName, routineName,
                        language, callingConvention);
        
        Long[] typeParameters = new Long[2];
        for (int i = 0; i < aliasInfo.getParameterCount(); i++) {
            String parameterName = aliasInfo.getParameterNames()[i];
            Parameter.Direction direction;
            switch (aliasInfo.getParameterModes()[i]) {
            case ParameterMetaData.parameterModeIn:
            default:
                direction = Parameter.Direction.IN;
                break;
            case ParameterMetaData.parameterModeOut:
                direction = Parameter.Direction.OUT;
                break;
            case ParameterMetaData.parameterModeInOut:
                direction = Parameter.Direction.INOUT;
                break;
            }
            Type builderType = TableDDL.columnType(aliasInfo.getParameterTypes()[i], typeParameters,
                                                   schemaName, routineName, parameterName);
            builder.parameter(schemaName, routineName,
                              parameterName, direction,
                              builderType.name(), typeParameters[0], typeParameters[1]);
        }
        
        if (aliasInfo.getReturnType() != null) {
            Type builderType = TableDDL.columnType(aliasInfo.getReturnType(), typeParameters,
                                                   schemaName, routineName, "return value");
            builder.parameter(schemaName, routineName,
                              null, Parameter.Direction.RETURN,
                              builderType.name(), typeParameters[0], typeParameters[1]);
        }

        if (createAlias.getExternalName() != null) {
            String className, methodName;
            boolean checkJarName;
            if (callingConvention == Routine.CallingConvention.JAVA) {
                className = createAlias.getJavaClassName();
                methodName = createAlias.getMethodName();
                checkJarName = true;
            }
            else if (callingConvention == Routine.CallingConvention.LOADABLE_PLAN) {
                // The whole class implements a standard interface.
                className = createAlias.getExternalName();
                methodName = null;
                checkJarName = true;
            }
            else {
                className = null;
                methodName = createAlias.getExternalName();
                checkJarName = false;
            }
            String jarSchema = null;
            String jarName = null;
            if (checkJarName) {
                int idx = className.indexOf(':');
                if (idx >= 0) {
                    jarName = className.substring(0, idx);
                    className = className.substring(idx + 1);
                    if (jarName.equals("thisjar")) {
                        TableName thisJar = (TableName)createAlias.getUserData();
                        jarSchema = thisJar.getSchemaName();
                        jarName = thisJar.getTableName();
                    }
                    else {
                        idx = jarName.indexOf('.');
                        if (idx < 0) {
                            jarSchema = defaultSchemaName;
                        }
                        else {
                            jarSchema = jarName.substring(0, idx);
                            jarName = jarName.substring(idx + 1);
                        }
                    }
                }
            }
            if (jarName != null) {
                AkibanInformationSchema ais = ddlFunctions.getAIS(session);
                SQLJJar sqljJar = ais.getSQLJJar(jarSchema, jarName);
                if (sqljJar == null)
                    throw new NoSuchSQLJJarException(jarSchema, jarName);
                builder.sqljJar(jarSchema, jarName, sqljJar.getURL());
            }
            builder.routineExternalName(schemaName, routineName, 
                                        jarSchema, jarName, 
                                        className, methodName);
        }
        if (createAlias.getDefinition() != null) {
            builder.routineDefinition(schemaName, routineName, 
                                      createAlias.getDefinition());
        }

        if (aliasInfo.getSQLAllowed() != null) {
            Routine.SQLAllowed sqlAllowed;
            switch (aliasInfo.getSQLAllowed()) {
            case MODIFIES_SQL_DATA:
                sqlAllowed = Routine.SQLAllowed.MODIFIES_SQL_DATA;
                break;
            case READS_SQL_DATA:
                sqlAllowed = Routine.SQLAllowed.READS_SQL_DATA;
                break;
            case CONTAINS_SQL:
                sqlAllowed = Routine.SQLAllowed.CONTAINS_SQL;
                break;
            case NO_SQL:
                sqlAllowed = Routine.SQLAllowed.NO_SQL;
                break;
            default:
                throw new InvalidRoutineException(schemaName, routineName, "unsupported " + aliasInfo.getSQLAllowed().getSQL());
            }
            builder.routineSQLAllowed(schemaName, routineName, sqlAllowed);
        }
        builder.routineDynamicResultSets(schemaName, routineName,
                                         aliasInfo.getMaxDynamicResultSets());
        
        Routine routine = builder.akibanInformationSchema().getRoutine(tableName);
        ddlFunctions.createRoutine(session, routine);
    }

    public static void dropRoutine(DDLFunctions ddlFunctions,
                                   RoutineLoader routineLoader,
                                   Session session,
                                   String defaultSchemaName,
                                   DropAliasNode dropRoutine,
                                   QueryContext context) {
        TableName routineName = DDLHelper.convertName(defaultSchemaName, dropRoutine.getObjectName());
        Routine routine = ddlFunctions.getAIS(session).getRoutine(routineName);
        
        if (routine == null) {
            throw new NoSuchRoutineException(routineName);
        } 
        routineLoader.unloadRoutine(session, routineName);
        ddlFunctions.dropRoutine(session, routineName);
    }
}