// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.filters;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.ArrayMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.muntashirakon.AppManager.filters.options.FilterOption;
import io.github.muntashirakon.AppManager.history.IJsonSerializer;
import io.github.muntashirakon.AppManager.history.JsonDeserializer;
import io.github.muntashirakon.util.ParcelUtils;

public class FilterItem implements IJsonSerializer, Parcelable {
    private static class ExprEvaluator extends AbsExpressionEvaluator {
        private final ArrayMap<Integer, FilterOption> mFilterOptions;
        @Nullable
        private IFilterableAppInfo mInfo;
        @Nullable
        private FilterOption.TestResult mResult;

        public ExprEvaluator(ArrayMap<Integer, FilterOption> filterOptions) {
            mFilterOptions = filterOptions;
        }

        public void setInfo(@Nullable IFilterableAppInfo info) {
            mInfo = info;
            mResult = new FilterOption.TestResult();
        }

        @Nullable
        public FilterOption.TestResult getResult() {
            return mResult;
        }

        @Override
        protected boolean evalId(@NonNull String id) {
            if (mResult == null) {
                mResult = new FilterOption.TestResult();
            }
            // Extract ID
            int idx = id.lastIndexOf('_');
            int intId;
            if (idx >= 0 && id.length() > (idx + 1)) {
                intId = Integer.parseInt(id.substring(idx + 1));
            } else intId = 0;
            FilterOption option = mFilterOptions.get(intId);
            if (option == null || mInfo == null) {
                return false;
            }
            return option.test(mInfo, mResult).isMatched();
        }
    }

    @NonNull
    private String mName;
    private final ArrayMap<Integer, FilterOption> mFilterOptions;
    private String mExpr = "";
    private boolean mCustomExpr = false;
    // Assign this id to the next filter option (starts with 1)
    private int mNextId = 1;

    public FilterItem() {
        this("Untitled");
    }

    private FilterItem(@NonNull String name) {
        mName = name;
        mFilterOptions = new ArrayMap<>();
    }

    @NonNull
    public String getName() {
        return mName;
    }

    public void setName(@NonNull String name) {
        mName = name;
    }

    @NonNull
    public String getExpr() {
        return mExpr;
    }

    public void setExpr(@NonNull String expr) {
        mExpr = expr;
        mCustomExpr = true;
    }

    public int addFilterOption(@NonNull FilterOption filterOption) {
        filterOption.id = getNextId();
        if (!mCustomExpr) {
            String id = filterOption.getFullId();
            // Add this to expr
            if (TextUtils.isEmpty(mExpr)) {
                mExpr = id;
            } else mExpr += " & " + id;
        }
        if (mFilterOptions.put(filterOption.id, filterOption) == null) {
            return mFilterOptions.indexOfKey(filterOption.id);
        }
        return -1;
    }

    public void updateFilterOptionAt(int i, @NonNull FilterOption filterOption) {
        FilterOption oldFilterOption = mFilterOptions.valueAt(i);
        if (oldFilterOption == null) {
            throw new IllegalArgumentException("Invalid index " + i);
        }
        filterOption.id = oldFilterOption.id;
        mFilterOptions.setValueAt(i, filterOption);
        if (!mCustomExpr) {
            String idStr = oldFilterOption.getFullId();
            // Default expression is just all the filters &'ed together
            String[] ops = mExpr.split(" & ");
            StringBuilder sb = new StringBuilder();
            for (String op : ops) {
                if (sb.length() > 0) {
                    sb.append(" & ");
                }
                if (idStr.equals(op)) {
                    sb.append(filterOption.getFullId());
                } else sb.append(op);
            }
            mExpr = sb.toString();
        }
    }

    public boolean removeFilterOptionAt(int i) {
        FilterOption filterOption = mFilterOptions.removeAt(i);
        if (filterOption == null) {
            return false;
        }
        mNextId = filterOption.id;
        if (!mCustomExpr) {
            String idStr = filterOption.getFullId();
            // Default expression is just all the filters &'ed together
            String[] ops = mExpr.split(" & ");
            StringBuilder sb = new StringBuilder();
            for (String op : ops) {
                if (!idStr.equals(op)) {
                    if (sb.length() > 0) {
                        sb.append(" & ");
                    }
                    sb.append(op);
                }
            }
            mExpr = sb.toString();
        }
        return true;
    }

    public int getSize() {
        return mFilterOptions.size();
    }

    public FilterOption getFilterOptionAt(int i) {
        return mFilterOptions.valueAt(i);
    }

    @Nullable
    public FilterOption getFilterOptionForId(int id) {
        return mFilterOptions.get(id);
    }

    public List<FilteredItemInfo> getFilteredList(@NonNull List<IFilterableAppInfo> allFilterableAppInfo) {
        List<FilteredItemInfo> filteredFilterableAppInfo = new ArrayList<>();
        ExprEvaluator evaluator = new ExprEvaluator(mFilterOptions);
        String expr = TextUtils.isEmpty(mExpr) ? "true" : mExpr;
        for (IFilterableAppInfo info : allFilterableAppInfo) {
            evaluator.setInfo(info);
            boolean eval = evaluator.evaluate(expr);
            FilterOption.TestResult result = Objects.requireNonNull(evaluator.getResult());
            if (eval) {
                filteredFilterableAppInfo.add(new FilteredItemInfo(info, result));
            }
        }
        return filteredFilterableAppInfo;
    }

    public FilterItem(@NonNull Parcel in) {
        mName = Objects.requireNonNull(in.readString());
        mExpr = Objects.requireNonNull(in.readString());
        mCustomExpr = in.readBoolean();
        mFilterOptions = ParcelUtils.readArrayMap(in, Integer.class.getClassLoader(), FilterOption.class.getClassLoader());
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeString(mExpr);
        dest.writeBoolean(mCustomExpr);
        ParcelUtils.writeMap(mFilterOptions, dest);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<FilterItem> CREATOR = new Creator<FilterItem>() {
        @Override
        @NonNull
        public FilterItem createFromParcel(@NonNull Parcel in) {
            return new FilterItem(in);
        }

        @Override
        @NonNull
        public FilterItem[] newArray(int size) {
            return new FilterItem[size];
        }
    };

    @NonNull
    @Override
    public JSONObject serializeToJson() throws JSONException {
        JSONObject object = new JSONObject();
        JSONArray array = new JSONArray();
        for (FilterOption filterOption : mFilterOptions.values()) {
            array.put(filterOption.toJson());
        }
        object.put("name", mName);
        object.put("expr", mExpr);
        object.put("custom_expr", mCustomExpr);
        object.put("options", array);
        return object;
    }

    public FilterItem(@NonNull JSONObject object) throws JSONException {
        mName = object.getString("name");
        mExpr = object.getString("expr");
        mCustomExpr = object.getBoolean("custom_expr");
        mFilterOptions = new ArrayMap<>();
        JSONArray array = object.getJSONArray("options");
        for (int i = 0; i < array.length(); ++i) {
            FilterOption option = FilterOption.fromJson(array.getJSONObject(i));
            mFilterOptions.put(option.id, option);
        }
    }

    public static final JsonDeserializer.Creator<FilterItem> DESERIALIZER = FilterItem::new;

    private int getNextId() {
        // Find next ID
        while (mFilterOptions.containsKey(mNextId)) {
            ++mNextId;
        }
        return mNextId;
    }

    public static class FilteredItemInfo {
        public final IFilterableAppInfo info;
        public final FilterOption.TestResult result;

        FilteredItemInfo(IFilterableAppInfo info, FilterOption.TestResult result) {
            this.info = info;
            this.result = result;
        }
    }
}
