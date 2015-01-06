/**
 * Odoo, Open Source Management Solution
 * Copyright (C) 2012-today Odoo SA (<http:www.odoo.com>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http:www.gnu.org/licenses/>
 *
 * Created on 31/12/14 6:54 PM
 */
package com.odoo.core.orm.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;

import com.odoo.core.orm.OModel;
import com.odoo.core.orm.OValues;
import com.odoo.core.orm.fields.OColumn;
import com.odoo.core.utils.JSONUtils;
import com.odoo.core.utils.ODateUtils;

import java.io.InvalidObjectException;
import java.util.HashSet;

public class BaseModelProvider extends ContentProvider {
    public static final String TAG = BaseModelProvider.class.getSimpleName();
    private final static String KEY_MODEL = "key_model";
    private final static String KEY_USERNAME = "key_username";
    private final int COLLECTION = 1;
    private final int SINGLE_ROW = 2;
    private OModel mModel = null;
    private UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);

    public static Uri buildURI(String authority, String model, String username) {
        Uri.Builder uriBuilder = new Uri.Builder();
        uriBuilder.authority(authority);
        uriBuilder.appendPath(model);
        uriBuilder.appendQueryParameter(KEY_MODEL, model);
        uriBuilder.appendQueryParameter(KEY_USERNAME, username);
        uriBuilder.scheme("content");
        return uriBuilder.build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }


    private void setModel(Uri uri) {
        String authority = uri.getAuthority();
        String path = uri.getQueryParameter(KEY_MODEL);
        String username = uri.getQueryParameter(KEY_USERNAME);
        matcher.addURI(authority, path, COLLECTION);
        matcher.addURI(authority, path + "/#", SINGLE_ROW);
        mModel = OModel.get(getContext(), path, username);
        assert mModel != null;
    }

    @Override
    public Cursor query(Uri uri, String[] base_projection, String selection, String[] selectionArgs, String sortOrder) {
        setModel(uri);
        String[] projection = removeRelationColumns(base_projection);
        int match = matcher.match(uri);
        SQLiteQueryBuilder builder = new SQLiteQueryBuilder();
        builder.setTables(mModel.getTableName());
        switch (match) {
            case COLLECTION:
                return builder.query(mModel.getReadableDatabase(), projection,
                        selection, selectionArgs, null, null, sortOrder);
            case SINGLE_ROW:
                int row_id = Integer.parseInt(uri.getLastPathSegment());
                return builder.query(mModel.getReadableDatabase(), projection,
                        OColumn.ROW_ID + " = ? ", new String[]{row_id + ""}, null, null, null);
            case UriMatcher.NO_MATCH:
                break;
            default:
                throw new UnsupportedOperationException("Unknown uri: " + uri);
        }
        return null;
    }

    private String[] removeRelationColumns(String[] projection) {
        HashSet<String> columns = new HashSet<>();
        if (projection != null && projection.length > 0) {
            for (String key : projection) {
                OColumn column = mModel.getColumn(key);
                if (column != null && column.getRelationType() == null) {
                    columns.add(key);
                } else if (column != null && column.getRelationType() == OColumn.RelationType.ManyToOne) {
                    columns.add(key);
                }
            }
            return columns.toArray(new String[columns.size()]);
        }
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return uri.toString();
    }

    @Override
    public Uri insert(Uri uri, ContentValues all_values) {
        setModel(uri);
        ContentValues[] values = generateValues(all_values);
        ContentValues value_to_insert = values[0];
        value_to_insert.put("_write_date", ODateUtils.getUTCDate());
        int match = matcher.match(uri);
        switch (match) {
            case COLLECTION:
                SQLiteDatabase db = mModel.getWritableDatabase();
                long new_id = 0;
                try {
                    new_id = db.insert(mModel.getTableName(), null, value_to_insert);
                } finally {
                    db.close();
                }

                // Updating relation columns for record
                if (values[1].size() > 0) {
                    storeUpdateRelationRecords(values[1], OColumn.ROW_ID + "  = ?", new String[]{new_id + ""});
                }

                return uri.withAppendedPath(uri, new_id + "");
            case SINGLE_ROW:
                throw new UnsupportedOperationException(
                        "Insert not supported on URI: " + uri);
            case UriMatcher.NO_MATCH:
                break;
            default:
                throw new UnsupportedOperationException("Unknown uri: " + uri);
        }
        // Send broadcast to registered ContentObservers, to refresh UI.
        Context ctx = getContext();
        assert ctx != null;
        ctx.getContentResolver().notifyChange(uri, null, false);
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int count = 0;
        setModel(uri);
        int match = matcher.match(uri);
        switch (match) {
            case COLLECTION:
                SQLiteDatabase db = mModel.getWritableDatabase();
                try {
                    count = db.delete(mModel.getTableName(), selection, selectionArgs);
                } finally {
                    db.close();
                }
                break;
            case SINGLE_ROW:
                db = mModel.getWritableDatabase();
                String row_id = uri.getLastPathSegment();
                try {
                    count = db.delete(mModel.getTableName(), OColumn.ROW_ID + "  = ?", new String[]{row_id});
                } finally {
                    db.close();
                }
                break;
            case UriMatcher.NO_MATCH:
                break;
            default:
                throw new UnsupportedOperationException("Unknown uri: " + uri);
        }
        // Send broadcast to registered ContentObservers, to refresh UI.
        Context ctx = getContext();
        assert ctx != null;
        ctx.getContentResolver().notifyChange(uri, null, false);
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues all_values, String selection, String[] selectionArgs) {
        setModel(uri);
        ContentValues[] values = generateValues(all_values);
        ContentValues value_to_update = values[0];
        value_to_update.put("_write_date", ODateUtils.getUTCDate());
        int count = 0;
        int match = matcher.match(uri);
        SQLiteDatabase db = mModel.getWritableDatabase();
        switch (match) {
            case COLLECTION:
                try {
                    count = db.update(mModel.getTableName(), value_to_update, selection, selectionArgs);
                } finally {
                    db.close();
                }
                // Updating relation columns
                if (values[1].size() > 0) {
                    storeUpdateRelationRecords(values[1], selection, selectionArgs);
                }

                break;
            case SINGLE_ROW:
                String row_id = uri.getLastPathSegment();
                try {
                    count = db.update(mModel.getTableName(), value_to_update, OColumn.ROW_ID + "  = ?", new String[]{row_id});
                    // Updating relation columns for record
                } finally {
                    db.close();
                }
                if (values[1].size() > 0) {
                    storeUpdateRelationRecords(values[1], OColumn.ROW_ID + "  = ?", new String[]{row_id});
                }
                break;
            case UriMatcher.NO_MATCH:
                break;
            default:
                throw new UnsupportedOperationException("Unknown uri: " + uri);
        }
        // Send broadcast to registered ContentObservers, to refresh UI.
        Context ctx = getContext();
        assert ctx != null;
        ctx.getContentResolver().notifyChange(uri, null, false);
        return count;
    }

    private void storeUpdateRelationRecords(ContentValues values, String selection, String[] args) {
        int row_id = mModel.selectRowId(selection, args);
        for (String key : values.keySet()) {
            try {
                mModel.storeManyToManyRecord(key,
                        row_id, JSONUtils.<Integer>toList(values.getAsString(key)),
                        OModel.Command.Replace);
            } catch (InvalidObjectException e) {
                e.printStackTrace();
            }
        }
    }

    private ContentValues[] generateValues(ContentValues values) {
        OValues data_value = new OValues();
        OValues rel_value = new OValues();
        for (String key : values.keySet()) {
            OColumn column = mModel.getColumn(key);
            if (column != null) {
                if (column.getRelationType() == null) {
                    data_value.put(key, values.get(key));
                } else {
                    if (column.getRelationType() == OColumn.RelationType.ManyToOne) {
                        data_value.put(key, values.get(key));
                    } else {
                        rel_value.put(key, values.get(key).toString());
                    }
                }
            }
        }
        return new ContentValues[]{data_value.toContentValues(), rel_value.toContentValues()};
    }

}
