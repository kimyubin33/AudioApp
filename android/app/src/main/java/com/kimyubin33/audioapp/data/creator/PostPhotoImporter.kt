package com.kimyubin33.audioapp.data.creator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 선택한 사진만 JPEG로 다시 인코딩한다. 원본 URI·GPS·EXIF를 초안/서버로 보내지 않는다. */
class PostPhotoImporter(private val context:Context,private val store:CreatorDraftStore) {
    fun import(uri:Uri):CreatorDraft {
        val bytes=context.contentResolver.openInputStream(uri)!!.use { input->
            val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val count=input.read(buffer);if(count<0)break;require(out.size()+count<=10*1024*1024);out.write(buffer,0,count)}
            out.toByteArray()
        }
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outMimeType in setOf("image/jpeg","image/png","image/webp")&&bounds.outWidth>0&&bounds.outHeight>0&&
            bounds.outWidth.toLong()*bounds.outHeight<=40_000_000)
        val options=BitmapFactory.Options().apply{
            var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2000)sample*=2;inSampleSize=sample
        }
        val bitmaps=mutableSetOf<Bitmap>()
        val id=UUID.randomUUID().toString();val target=store.file(id)
        try {
            val decoded=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)).also{bitmaps+=it}
            val exif=ExifInterface(ByteArrayInputStream(bytes))
            val matrix=Matrix()
            when(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)){
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL->matrix.setScale(-1f,1f)
                ExifInterface.ORIENTATION_ROTATE_180->matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL->matrix.setScale(1f,-1f)
                ExifInterface.ORIENTATION_TRANSPOSE->{matrix.setRotate(90f);matrix.postScale(-1f,1f)}
                ExifInterface.ORIENTATION_ROTATE_90->matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE->{matrix.setRotate(270f);matrix.postScale(-1f,1f)}
                ExifInterface.ORIENTATION_ROTATE_270->matrix.setRotate(270f)
            }
            val upright=Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true).also{bitmaps+=it}
            val scale=minOf(1f,1600f/maxOf(upright.width,upright.height))
            val image=Bitmap.createScaledBitmap(upright,maxOf(1,(upright.width*scale).toInt()),maxOf(1,(upright.height*scale).toInt()),true).also{bitmaps+=it}
            target.outputStream().use{check(image.compress(Bitmap.CompressFormat.JPEG,90,it))}
            return draft(target,id,image.width,image.height)
        } catch(e:Exception){target.delete();throw e}
        finally{bitmaps.forEach{it.recycle()}}
    }
    fun copy(file:File):CreatorDraft {
        require(file.isFile&&file.length() in 1..10*1024*1024)
        val id=UUID.randomUUID().toString();val target=store.file(id)
        try{file.copyTo(target);return draft(target,id,0,0)}catch(e:Exception){target.delete();throw e}
    }
    private fun draft(file:File,id:String,width:Int,height:Int):CreatorDraft {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        return CreatorDraft(fileId=id,name="moment.jpg",source="선택한 사진 · 위치 정보 제거",bytes=file.length(),
            sha256=digest.digest().joinToString(""){"%02x".format(it)},isPost=true,aiGenerated=false,width=width,height=height)
    }
}
