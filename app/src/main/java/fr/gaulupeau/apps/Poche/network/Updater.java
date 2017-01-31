package fr.gaulupeau.apps.Poche.network;

import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.di72nn.stuff.wallabag.apiwrapper.WallabagService;
import com.di72nn.stuff.wallabag.apiwrapper.exceptions.UnsuccessfulResponseException;
import com.di72nn.stuff.wallabag.apiwrapper.models.Articles;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.gaulupeau.apps.Poche.data.Settings;
import fr.gaulupeau.apps.Poche.data.dao.ArticleDao;
import fr.gaulupeau.apps.Poche.data.dao.ArticleTagsJoinDao;
import fr.gaulupeau.apps.Poche.data.dao.DaoSession;
import fr.gaulupeau.apps.Poche.data.dao.TagDao;
import fr.gaulupeau.apps.Poche.data.dao.entities.Article;
import fr.gaulupeau.apps.Poche.data.dao.entities.ArticleTagsJoin;
import fr.gaulupeau.apps.Poche.data.dao.entities.Tag;
import fr.gaulupeau.apps.Poche.events.ArticlesChangedEvent;

public class Updater {

    public enum UpdateType { FULL, FAST }

    private static final String TAG = Updater.class.getSimpleName();

    private final Settings settings;
    private final DaoSession daoSession;
    private final WallabagServiceWrapper wallabagServiceWrapper;

    public Updater(Settings settings, DaoSession daoSession,
                   WallabagServiceWrapper wallabagServiceWrapper) {
        this.settings = settings;
        this.daoSession = daoSession;
        this.wallabagServiceWrapper = wallabagServiceWrapper;
    }

    public ArticlesChangedEvent update(UpdateType updateType)
            throws UnsuccessfulResponseException, IOException {
        boolean clean = updateType != UpdateType.FAST;

        Log.i(TAG, "update() started; clean: " + clean);

        ArticlesChangedEvent event = new ArticlesChangedEvent();

        long latestUpdatedItemTimestamp = 0;

        daoSession.getDatabase().beginTransaction();
        try {
            if(clean) {
                Log.d(TAG, "update() deleting old DB entries");
                daoSession.getArticleDao().deleteAll();
                daoSession.getTagDao().deleteAll();
                daoSession.getArticleTagsJoinDao().deleteAll();

                event.setInvalidateAll(true);
            }

            latestUpdatedItemTimestamp = settings.getLatestUpdatedItemTimestamp();
            Log.v(TAG, "update() latestUpdatedItemTimestamp: " + latestUpdatedItemTimestamp);

            Log.d(TAG, "update() updating articles");
            latestUpdatedItemTimestamp = performUpdate(event, clean, latestUpdatedItemTimestamp);
            Log.d(TAG, "update() articles updated");
            Log.v(TAG, "update() latestUpdatedItemTimestamp: " + latestUpdatedItemTimestamp);

            daoSession.getDatabase().setTransactionSuccessful();
        } finally {
            daoSession.getDatabase().endTransaction();
        }

        settings.setLatestUpdatedItemTimestamp(latestUpdatedItemTimestamp);
        settings.setLatestUpdateRunTimestamp(System.currentTimeMillis());
        settings.setFirstSyncDone(true);

        Log.i(TAG, "update() finished");

        return event;
    }

    private long performUpdate(ArticlesChangedEvent event, boolean full,
                               long latestUpdatedItemTimestamp)
            throws UnsuccessfulResponseException, IOException {
        Log.d(TAG, String.format("performUpdate(full: %s, latestUpdatedItemTimestamp: %d) started",
                full, latestUpdatedItemTimestamp));

        ArticleDao articleDao = daoSession.getArticleDao();
        TagDao tagDao = daoSession.getTagDao();
        ArticleTagsJoinDao articleTagsJoinDao = daoSession.getArticleTagsJoinDao();

        List<Tag> tags;
        if(full) {
            List<com.di72nn.stuff.wallabag.apiwrapper.models.Tag> apiTags
                    = wallabagServiceWrapper.getWallabagService().getTags();

            tags = new ArrayList<>(apiTags.size());

            for(com.di72nn.stuff.wallabag.apiwrapper.models.Tag apiTag: apiTags) {
                tags.add(new Tag(null, apiTag.id, apiTag.label));
            }

            tagDao.insertInTx(tags);
        } else {
            tags = tagDao.queryBuilder().list();
        }

        SparseArray<Tag> tagMap = new SparseArray<>(tags.size());
        for(Tag tag: tags) tagMap.put(tag.getTagId(), tag);

        WallabagService.ArticlesQueryBuilder articlesQueryBuilder
                = wallabagServiceWrapper.getWallabagService().getArticlesBuilder();

        if(full) {
            articlesQueryBuilder
                    .sortCriterion(WallabagService.SortCriterion.CREATED)
                    .sortOrder(WallabagService.SortOrder.ASCENDING);

            latestUpdatedItemTimestamp = 0;
        } else {
            articlesQueryBuilder
                    .sortCriterion(WallabagService.SortCriterion.UPDATED)
                    .sortOrder(WallabagService.SortOrder.ASCENDING)
                    .since(latestUpdatedItemTimestamp / 1000); // convert milliseconds to seconds
        }

        WallabagService.ArticlesPageIterator pageIterator = articlesQueryBuilder
                .perPage(30).pageIterator();

        List<Article> articlesToUpdate = new ArrayList<>();
        List<Article> articlesToInsert = new ArrayList<>();
        Set<Tag> tagsToUpdate = new HashSet<>();
        List<Tag> tagsToInsert = new ArrayList<>();
        Map<Article, List<Tag>> articleTagJoinsToRemove = new HashMap<>();
        Map<Article, List<Tag>> articleTagJoinsToInsert = new HashMap<>();

        Log.d(TAG, "performUpdate() starting to iterate though pages");
        while(pageIterator.hasNext()) {
            Articles articles = pageIterator.next();

            Log.d(TAG, String.format("performUpdate() page: %d/%d, total articles: %d",
                    articles.page, articles.pages, articles.total));

            if(articles.embedded.items.isEmpty()) {
                Log.d(TAG, "performUpdate() no items; skipping");
                continue;
            }

            articlesToUpdate.clear();
            articlesToInsert.clear();
            tagsToUpdate.clear();
            tagsToInsert.clear();
            articleTagJoinsToRemove.clear();
            articleTagJoinsToInsert.clear();

            for(com.di72nn.stuff.wallabag.apiwrapper.models.Article apiArticle: articles.embedded.items) {
                int id = apiArticle.id;

                Article article = null;

                if(!full) {
                    article = articleDao.queryBuilder()
                            .where(ArticleDao.Properties.ArticleId.eq(id)).build().unique();
                }

                boolean existing = true;
                if(article == null) {
                    article = new Article(null);
                    existing = false;
                }

                // TODO: change detection?

                if(!existing || (article.getImagesDownloaded()
                        && !TextUtils.equals(article.getContent(), apiArticle.content))) {
                    article.setImagesDownloaded(false);
                }

                article.setTitle(apiArticle.title);
                article.setContent(apiArticle.content);
                article.setUrl(apiArticle.url);
                article.setArticleId(id);
                article.setUpdateDate(apiArticle.updatedAt);
                article.setArchive(apiArticle.archived);
                article.setFavorite(apiArticle.starred);

                List<Tag> articleTags;
                if(existing) {
                    articleTags = article.getTags();
                    List<Tag> tagJoinsToRemove = null;

                    for(Tag tag: articleTags) {
                        com.di72nn.stuff.wallabag.apiwrapper.models.Tag apiTag
                                = findApiTagByID(tag.getTagId(), apiArticle.tags);
                        apiArticle.tags.remove(apiTag);

                        if(apiTag == null) {
                            if(tagJoinsToRemove == null) tagJoinsToRemove = new ArrayList<>();

                            tagJoinsToRemove.add(tag);
                        } else if(!TextUtils.equals(tag.getLabel(), apiTag.label)) {
                            tag.setLabel(apiTag.label);

                            tagsToUpdate.add(tag);
                        }
                    }

                    if(tagJoinsToRemove != null && !tagJoinsToRemove.isEmpty()) {
                        articleTags.removeAll(tagJoinsToRemove);
                        articleTagJoinsToRemove.put(article, tagJoinsToRemove);
                    }
                } else {
                    articleTags = new ArrayList<>(apiArticle.tags.size());
                    article.setTags(articleTags);
                }

                if(!apiArticle.tags.isEmpty()) {
                    List<Tag> tagJoinsToInsert = new ArrayList<>(apiArticle.tags.size());

                    for(com.di72nn.stuff.wallabag.apiwrapper.models.Tag apiTag: apiArticle.tags) {
                        Tag tag = tagMap.get(apiTag.id);

                        if(tag == null) {
                            tag = new Tag(null, apiTag.id, apiTag.label);
                            tagMap.put(tag.getTagId(), tag);

                            tagsToInsert.add(tag);
                        } else if(!TextUtils.equals(tag.getLabel(), apiTag.label)) {
                            tag.setLabel(apiTag.label);

                            tagsToUpdate.add(tag);
                        }

                        articleTags.add(tag);
                        tagJoinsToInsert.add(tag);
                    }

                    if(!tagJoinsToInsert.isEmpty()) {
                        articleTagJoinsToInsert.put(article, tagJoinsToInsert);
                    }
                }

                if(apiArticle.updatedAt.getTime() > latestUpdatedItemTimestamp) {
                    latestUpdatedItemTimestamp = apiArticle.updatedAt.getTime();
                }

                if(event != null) {
                    ArticlesChangedEvent.ChangeType changeType = existing
                            ? ArticlesChangedEvent.ChangeType.UNSPECIFIED
                            : ArticlesChangedEvent.ChangeType.ADDED;

                    event.setInvalidateAll(true); // improve?
                    event.addChangedArticleID(article, changeType);
                }

                (existing ? articlesToUpdate : articlesToInsert).add(article);
            }

            if(!articlesToUpdate.isEmpty()) {
                Log.v(TAG, "performUpdate() performing articleDao.updateInTx()");
                articleDao.updateInTx(articlesToUpdate);
                Log.v(TAG, "performUpdate() done articleDao.updateInTx()");

                articlesToUpdate.clear();
            }

            if(!articlesToInsert.isEmpty()) {
                Log.v(TAG, "performUpdate() performing articleDao.insertInTx()");
                articleDao.insertInTx(articlesToInsert);
                Log.v(TAG, "performUpdate() done articleDao.insertInTx()");

                articlesToInsert.clear();
            }

            if(!tagsToUpdate.isEmpty()) {
                Log.v(TAG, "performUpdate() performing tagDao.updateInTx()");
                tagDao.updateInTx(tagsToUpdate);
                Log.v(TAG, "performUpdate() done tagDao.updateInTx()");

                tagsToUpdate.clear();
            }

            if(!tagsToInsert.isEmpty()) {
                Log.v(TAG, "performUpdate() performing tagDao.insertInTx()");
                tagDao.insertInTx(tagsToInsert);
                Log.v(TAG, "performUpdate() done tagDao.insertInTx()");

                tagsToInsert.clear();
            }

            if(!articleTagJoinsToRemove.isEmpty()) {
                List<ArticleTagsJoin> joins = new ArrayList<>();

                for(Map.Entry<Article, List<Tag>> entry: articleTagJoinsToRemove.entrySet()) {
                    List<Long> tagIDsToRemove = new ArrayList<>(entry.getValue().size());
                    for(Tag tag: entry.getValue()) tagIDsToRemove.add(tag.getId());

                    joins.addAll(articleTagsJoinDao.queryBuilder().where(
                            ArticleTagsJoinDao.Properties.ArticleId.eq(entry.getKey().getId()),
                            ArticleTagsJoinDao.Properties.TagId.in(tagIDsToRemove)).list());
                }

                articleTagJoinsToRemove.clear();

                Log.v(TAG, "performUpdate() performing articleTagsJoinDao.deleteInTx()");
                articleTagsJoinDao.deleteInTx(joins);
                Log.v(TAG, "performUpdate() done articleTagsJoinDao.deleteInTx()");
            }

            if(!articleTagJoinsToInsert.isEmpty()) {
                List<ArticleTagsJoin> joins = new ArrayList<>();

                for(Map.Entry<Article, List<Tag>> entry: articleTagJoinsToInsert.entrySet()) {
                    for(Tag tag: entry.getValue()) {
                        joins.add(new ArticleTagsJoin(null, entry.getKey().getId(), tag.getId()));
                    }
                }

                articleTagJoinsToInsert.clear();

                Log.v(TAG, "performUpdate() performing articleTagsJoinDao.insertInTx()");
                articleTagsJoinDao.insertInTx(joins);
                Log.v(TAG, "performUpdate() done articleTagsJoinDao.insertInTx()");
            }
        }

        return latestUpdatedItemTimestamp;
    }

    private com.di72nn.stuff.wallabag.apiwrapper.models.Tag findApiTagByID(
            int id, List<com.di72nn.stuff.wallabag.apiwrapper.models.Tag> tags) {
        for(com.di72nn.stuff.wallabag.apiwrapper.models.Tag tag: tags) {
            if(id == tag.id) return tag;
        }

        return null;
    }

}
