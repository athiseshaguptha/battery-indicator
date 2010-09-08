/*
    Copyright (c) 2010 Josiah Barber (aka Darshan)

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/

package com.darshancomputing.BatteryIndicatorPro;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.res.Resources;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.method.LinkMovementMethod;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.DateFormat;
import java.util.Date;

public class LogViewActivity extends ListActivity {
    private LogDatabase logs;
    private Resources res;
    private Context context;
    private SharedPreferences settings;
    private Str str;
    private Col col;
    private Cursor mCursor;
    private SimpleCursorAdapter mAdapter;
    private LogViewBinder mBinder;
    private TextView logs_header;
    private Boolean reversed = false;
    private Boolean convertF;

    private static final int DIALOG_CONFIRM_CLEAR_LOGS = 0;

    private static final String[] KEYS = {LogDatabase.KEY_STATUS_CODE, LogDatabase.KEY_CHARGE, LogDatabase.KEY_TIME,
                                          LogDatabase.KEY_TEMPERATURE, LogDatabase.KEY_VOLTAGE};
    private static final int[]     IDS = {R.id.status, R.id.percent, R.id.time, R.id.temp_volt, R.id.temp_volt};

    private static final String[] CSV_ORDER = {LogDatabase.KEY_TIME, LogDatabase.KEY_STATUS_CODE, LogDatabase.KEY_CHARGE, 
                                               LogDatabase.KEY_TEMPERATURE, LogDatabase.KEY_VOLTAGE};

    private static final IntentFilter batteryChangedFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    private final Handler mHandler = new Handler();
    private final Runnable mUpdateStatus = new Runnable() {
        public void run() {
            reloadList(false);
        }
    };

    private final BroadcastReceiver mBatteryInfoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (! Intent.ACTION_BATTERY_CHANGED.equals(action)) return;

            /* Give the service a couple seconds to process the update */
            mHandler.postDelayed(mUpdateStatus, 2 * 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        context = getApplicationContext();
        res = getResources();
        settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        convertF = settings.getBoolean(SettingsActivity.KEY_CONVERT_F, false);
        str = new Str(res);
        col = new Col();

        logs_header = (TextView) View.inflate(context, R.layout.logs_header, null);
        getListView().addHeaderView(logs_header);

        logs = new LogDatabase(context);
        mCursor = logs.getAllLogs(false);
        startManagingCursor(mCursor);

        mAdapter = new SimpleCursorAdapter(context, R.layout.log_item, mCursor, KEYS, IDS);
        mBinder  = new LogViewBinder(context, mCursor);
        mAdapter.setViewBinder(mBinder);
        setListAdapter(mAdapter);

        setHeaderText();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mCursor.close();
        logs.close();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mBatteryInfoReceiver, batteryChangedFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mBatteryInfoReceiver);
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        Dialog dialog;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        switch (id) {
        case DIALOG_CONFIRM_CLEAR_LOGS:
            builder.setTitle(str.confirm_clear_logs)
                .setPositiveButton(str.yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface di, int id) {
                            logs.clearAllLogs();
                            reloadList(false);

                            di.cancel();
                        }
                    })
                .setNegativeButton(str.cancel, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface di, int id) {
                            di.cancel();
                        }
                    });

            dialog = builder.create();
            break;
        default:
            dialog = null;
        }

        return dialog;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.logs, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        switch (mCursor.getCount()) {
        case 0:
            menu.findItem(R.id.menu_clear).setEnabled(false);
            menu.findItem(R.id.menu_export).setEnabled(false);
            menu.findItem(R.id.menu_reverse).setEnabled(false);
            break;
        case 1:
            menu.findItem(R.id.menu_clear).setEnabled(true);
            menu.findItem(R.id.menu_export).setEnabled(true);
            menu.findItem(R.id.menu_reverse).setEnabled(false);
            break;
        default:
            menu.findItem(R.id.menu_clear).setEnabled(true);
            menu.findItem(R.id.menu_export).setEnabled(true);
            menu.findItem(R.id.menu_reverse).setEnabled(true);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.menu_clear:
            showDialog(DIALOG_CONFIRM_CLEAR_LOGS);

            return true;
        case R.id.menu_export:
            exportCSV();

            return true;
        case R.id.menu_reverse:
            reversed = (reversed) ? false : true;
            reloadList(true);

            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    private void reloadList(Boolean newQuery){
        if (newQuery) {
            stopManagingCursor(mCursor);
            mCursor = logs.getAllLogs(reversed);
            startManagingCursor(mCursor);

            mAdapter.changeCursor(mCursor);
        } else {
            mCursor.requery();
        }

        setHeaderText();
    }

    private void setHeaderText() {
        int count = mCursor.getCount();

        if (count == 0)
            logs_header.setText(str.logs_empty);
        else
            logs_header.setText(str.n_log_items(count));
    }

    private void exportCSV() {
        String state = Environment.getExternalStorageState();

        if (state != null && state.equals(Environment.MEDIA_MOUNTED_READ_ONLY)) {
            Toast.makeText(context, str.read_only_storage, Toast.LENGTH_SHORT).show();
            return;
        } else if (state == null || !state.equals(Environment.MEDIA_MOUNTED)) {
            Toast.makeText(context, str.inaccessible_w_reason + state, Toast.LENGTH_SHORT).show();
            return;
        }

        Date d = new Date();
        String csvFileName = "BatteryIndicatorPro-Logs-" + d.getTime() + ".csv";

        File root    = Environment.getExternalStorageDirectory();
        File csvFile = new File(root, csvFileName);

        String[] csvFields = {str.date, str.time, str.status, str.charge, str.temperature, str.voltage};

        try {
            if (!csvFile.createNewFile() || !csvFile.canWrite()) {
                Toast.makeText(context, str.inaccessible_storage, Toast.LENGTH_SHORT).show();
                return;
            }

            BufferedWriter buf = new BufferedWriter(new FileWriter(csvFile));

            int cols = csvFields.length;
            int i;
            for (i = 0; i < cols; i++) {
                buf.write(csvFields[i]);
                if (i != cols - 1) buf.write(",");
            }
            buf.write("\r\n");

            int statusCode;
            int[] statusCodes;
            int status, plugged, status_age;
            String s;

            for (mCursor.moveToFirst(); !mCursor.isAfterLast(); mCursor.moveToNext()) {
                cols = CSV_ORDER.length;
                for (i = 0; i < cols; i++) {
                    if (CSV_ORDER[i].equals(LogDatabase.KEY_TIME)) {
                        d.setTime(mCursor.getLong(mBinder.timeIndex));
                        buf.write(mBinder.dateFormat.format(d) + "," + mBinder.timeFormat.format(d) + ",");
                    } else if (CSV_ORDER[i].equals(LogDatabase.KEY_STATUS_CODE)) {
                        statusCode  = mCursor.getInt(mBinder.statusCodeIndex);
                        statusCodes = LogDatabase.decodeStatus(statusCode);
                        status      = statusCodes[0];
                        plugged     = statusCodes[1];
                        status_age  = statusCodes[2];

                        if (status_age == LogDatabase.STATUS_OLD)
                            s = str.log_statuses_old[status];
                        else
                            s = str.log_statuses[status];
                        if (plugged > 0)
                            s += " " + str.pluggeds[plugged];

                        buf.write(s + ",");
                    } else if (CSV_ORDER[i].equals(LogDatabase.KEY_CHARGE)) {
                        buf.write(String.valueOf(mCursor.getInt(mBinder.chargeIndex)) + ",");
                    } else if (CSV_ORDER[i].equals(LogDatabase.KEY_TEMPERATURE)) {
                        buf.write(String.valueOf(mCursor.getInt(mBinder.temperatureIndex) / 10.0) + ",");
                    } else if (CSV_ORDER[i].equals(LogDatabase.KEY_VOLTAGE)) {
                        buf.write(String.valueOf(mCursor.getInt(mBinder.voltageIndex) / 1000.0));
                    }
                }
                buf.write("\r\n");
            }
            buf.close();
        } catch (Exception e) {
            Toast.makeText(context, str.inaccessible_storage, Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(context, str.file_written, Toast.LENGTH_SHORT).show();
    }

    private class LogViewBinder implements SimpleCursorAdapter.ViewBinder {
        public int statusCodeIndex, chargeIndex, timeIndex, temperatureIndex, voltageIndex;
        public DateFormat dateFormat, timeFormat;

        private Date d = new Date();

        public LogViewBinder(Context context, Cursor cursor) {
            dateFormat = android.text.format.DateFormat.getDateFormat(context);
            timeFormat = android.text.format.DateFormat.getTimeFormat(context);

             statusCodeIndex = cursor.getColumnIndexOrThrow(LogDatabase.KEY_STATUS_CODE);
                 chargeIndex = cursor.getColumnIndexOrThrow(LogDatabase.KEY_CHARGE);
                   timeIndex = cursor.getColumnIndexOrThrow(LogDatabase.KEY_TIME);
            temperatureIndex = cursor.getColumnIndexOrThrow(LogDatabase.KEY_TEMPERATURE);
                voltageIndex = cursor.getColumnIndexOrThrow(LogDatabase.KEY_VOLTAGE);
        }

        @Override
        public boolean setViewValue(View view, Cursor cursor, int columnIndex) {
            TextView tv = (TextView) view;

            if (columnIndex == statusCodeIndex) {
                int statusCode = cursor.getInt(columnIndex);
                int[] statusCodes = LogDatabase.decodeStatus(statusCode);
                int status     = statusCodes[0];
                int plugged    = statusCodes[1];
                int status_age = statusCodes[2];

                TextView percent_tv = getSibling(tv, R.id.percent);
                String s;

                if (status_age == LogDatabase.STATUS_OLD) {
                            tv.setTextColor(col.old_status);
                    percent_tv.setTextColor(col.old_status);
                    s = str.log_statuses_old[status];
                } else {
                    switch (status) {
                    case 5:
                                tv.setTextColor(col.charged);
                        percent_tv.setTextColor(col.charged);
                        break;
                    case 0:
                                tv.setTextColor(col.unplugged);
                        percent_tv.setTextColor(col.unplugged);
                        break;
                    case 2:
                    default:
                                tv.setTextColor(col.plugged);
                        percent_tv.setTextColor(col.plugged);
                    }

                    s = str.log_statuses[status];
                }

                if (plugged > 0)
                    s += " " + str.pluggeds[plugged];

                tv.setText(s);
                return true;
            } else if (columnIndex == chargeIndex) {
                tv.setText("" + cursor.getInt(columnIndex) + "%");
                return true;
            } else if (columnIndex == timeIndex) {
                d.setTime(cursor.getLong(columnIndex));
                tv.setText(dateFormat.format(d) + "  " + timeFormat.format(d));
                return true;
            } else if (columnIndex == temperatureIndex) {
                int temperature = cursor.getInt(columnIndex);
                if (temperature != 0) tv.setText("" + str.formatTemp(temperature, convertF));
                else tv.setText(""); /* TextView are reused */
                return true;
            } else if (columnIndex == voltageIndex) {
                int voltage = cursor.getInt(columnIndex);
                if (voltage != 0) tv.setText(((String) tv.getText()) + " / " + str.formatVoltage(voltage));
                return true;
            } else {
                return false;
            }
        }
    }

    private static TextView getSibling(TextView myself, int siblingId) {
        return (TextView) ((ViewGroup) myself.getParent()).findViewById(siblingId);
    }

    private class Col {
        public int old_status;
        public int charged;
        public int plugged;
        public int unplugged;

        public Col() {
            old_status = res.getColor(R.color.old_status);
            charged    = res.getColor(R.color.charged);
            plugged    = res.getColor(R.color.plugged);
            unplugged  = res.getColor(R.color.unplugged);
        }
    }
}
