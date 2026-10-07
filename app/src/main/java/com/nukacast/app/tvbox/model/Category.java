package com.nukacast.app.tvbox.model;

/**
 * One browsable category of a site, as declared by its configuration.
 *
 * <p>TVBox configs describe categories either as {@code {"type_id":1,"type_name":"电影"}} or as
 * plain strings; both shapes are normalised into this type so the UI can show a category bar.
 */
public final class Category {
    public String id = "";
    public String name = "";
    /** Which site the category belongs to, for the browse request. */
    public String siteKey = "";
    public String siteName = "";
    public String sourceId = "";

    public Category() {}

    public Category(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public boolean isValid() {
        return id != null && !id.isEmpty() && name != null && !name.isEmpty();
    }
}
