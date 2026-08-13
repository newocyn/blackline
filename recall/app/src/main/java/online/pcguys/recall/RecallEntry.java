package online.pcguys.recall;

public class RecallEntry {
    public final long id;
    public final String sourcePackage;
    public final String sourceName;
    public final String title;
    public final String text;
    public final long createdAt;
    public final boolean favorite;

    public RecallEntry(long id, String sourcePackage, String sourceName, String title, String text, long createdAt, boolean favorite) {
        this.id = id;
        this.sourcePackage = sourcePackage;
        this.sourceName = sourceName;
        this.title = title;
        this.text = text;
        this.createdAt = createdAt;
        this.favorite = favorite;
    }
}
