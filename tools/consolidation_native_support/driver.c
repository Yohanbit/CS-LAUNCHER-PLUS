#include <assert.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <elf.h>
#include <sys/mman.h>
#include <android/dlext.h>
/* Compile the complete production helper in this TU to exercise its bounded
 * static instruction scanner. Only Android linker/system calls are doubled. */
#include "driver_helper/nsbypass.c"

/* Preserve the loader double above; give the production interposition exports
 * unique test names, without changing any hook decision logic. */
#define android_dlopen_ext consolidation_hook_dlopen
#include "driver_helper/hook.c"
#undef android_dlopen_ext
static int private_ext_calls, namespace_available;
static void *private_ext(const char *name,int flags,const android_dlextinfo *info,const void *caller) {
    assert(name&&caller);private_ext_calls++;
    if(info) {
        assert(info->flags==ANDROID_DLEXT_USE_NAMESPACE&&info->library_namespace==(void*)1);
        assert(info->reserved_addr==NULL&&info->reserved_size==0&&info->library_fd==0&&info->library_fd_offset==0);
    }
    return (void*)9;
}
static struct android_namespace_t *exported_namespace(const char *name) { return namespace_available?(void*)1:NULL; }

static int mmap_failure, sync_failure, maps, unmaps, loader_failure, loader_opens;
static int last_loader_fd;
void *__real_mmap(void*,size_t,int,int,int,off_t);
int __real_munmap(void*,size_t);
int __real_msync(void*,size_t,int);
void *__wrap_mmap(void *address,size_t length,int prot,int flags,int fd,off_t offset) {
    if(mmap_failure){errno=ENOMEM;return MAP_FAILED;}
    void *p=__real_mmap(address,length,prot,flags,fd,offset);if(p!=MAP_FAILED)maps++;return p;
}
int __wrap_munmap(void *p,size_t length) { unmaps++;return __real_munmap(p,length); }
int __wrap_msync(void *p,size_t length,int flags) { if(sync_failure){errno=EIO;return -1;}return __real_msync(p,length,flags); }
void *android_dlopen_ext(const char *name,int flags,const android_dlextinfo *info) {
    assert(name&&info&&info->library_namespace==(void*)1);
    assert(info->flags==(ANDROID_DLEXT_USE_NAMESPACE|ANDROID_DLEXT_USE_LIBRARY_FD));
    assert(info->reserved_addr==NULL&&info->reserved_size==0&&info->library_fd_offset==0);
    assert(fcntl(info->library_fd,F_GETFD)!=-1);last_loader_fd=info->library_fd;loader_opens++;
    return loader_failure?NULL:(void*)0x1234;
}
static void elf_image(unsigned char image[512]) {
    memset(image,0,512);
    Elf64_Ehdr e={0};memcpy(e.e_ident,ELFMAG,SELFMAG);e.e_ident[EI_CLASS]=ELFCLASS64;e.e_ident[EI_DATA]=ELFDATA2LSB;e.e_ident[EI_VERSION]=EV_CURRENT;
    e.e_version=EV_CURRENT;e.e_type=ET_DYN;e.e_machine=EM_AARCH64;e.e_ehsize=sizeof(e);e.e_shoff=64;e.e_shnum=3;e.e_shentsize=sizeof(Elf64_Shdr);
    memcpy(image,&e,sizeof(e));
    Elf64_Shdr d={.sh_type=SHT_DYNAMIC,.sh_offset=256,.sh_size=2*sizeof(Elf64_Dyn),.sh_link=2,.sh_entsize=sizeof(Elf64_Dyn)};
    Elf64_Shdr s={.sh_type=SHT_STRTAB,.sh_offset=320,.sh_size=32};
    memcpy(image+128,&d,sizeof(d));memcpy(image+192,&s,sizeof(s));
    Elf64_Dyn dyn={.d_tag=DT_SONAME,.d_un.d_val=0};memcpy(image+256,&dyn,sizeof(dyn));strcpy((char*)image+320,"libvulkan.so");
}
static void write_image(int fd,unsigned char *image) { assert(ftruncate(fd,0)==0);assert(pwrite(fd,image,512,0)==512); }
static void patch_case(int patch,int real,unsigned char *image,int expect) {
    write_image(real,image);int before_maps=maps,before_unmaps=unmaps;
    assert(patch_elf_soname(patch,real,0xabc)==expect);
    assert(maps-before_maps==unmaps-before_unmaps);
    assert(fcntl(real,F_GETFD)!=-1&&fcntl(patch,F_GETFD)!=-1);
}
int main(int argc,char **argv) {
    assert(argc==2);char realpath[PATH_MAX],patchpath[PATH_MAX];
    assert(snprintf(realpath,sizeof(realpath),"%s/libvulkan.so",argv[1])>0);
    assert(snprintf(patchpath,sizeof(patchpath),"%s/patch",argv[1])>0);
    int real=open(realpath,O_CREAT|O_RDWR,0600),patch=open(patchpath,O_CREAT|O_RDWR,0600);assert(real>=0&&patch>=0);
    unsigned char image[512];elf_image(image);patch_case(patch,real,image,1);
    char prefix[4]={0};assert(pread(patch,prefix,3,320)==3&&!strcmp(prefix,"abc"));
    mmap_failure=1;patch_case(patch,real,image,0);mmap_failure=0;
    sync_failure=1;patch_case(patch,real,image,0);sync_failure=0;
    for(int field=0;field<12;field++) {
        elf_image(image);Elf64_Ehdr e;Elf64_Shdr d,s;Elf64_Dyn dyn;
        memcpy(&e,image,sizeof(e));memcpy(&d,image+128,sizeof(d));memcpy(&s,image+192,sizeof(s));memcpy(&dyn,image+256,sizeof(dyn));
        switch(field) {
            case 0:e.e_ident[0]=0;break;case 1:e.e_ident[EI_CLASS]=ELFCLASS32;break;
            case 2:e.e_shoff=UINT64_MAX-16;break;case 3:e.e_shentsize=0;break;
            case 4:d.sh_link=3;break;case 5:d.sh_entsize=0;break;
            case 6:d.sh_offset=UINT64_MAX;break;case 7:s.sh_offset=UINT64_MAX;break;
            case 8:dyn.d_un.d_val=32;break;case 9:dyn.d_tag=DT_NULL;break;
            case 10:s.sh_size=2;break;case 11:memset(image+320,'X',32);break;
        }
        memcpy(image,&e,sizeof(e));memcpy(image+128,&d,sizeof(d));memcpy(image+192,&s,sizeof(s));memcpy(image+256,&dyn,sizeof(dyn));
        patch_case(patch,real,image,0);
    }
    elf_image(image);write_image(real,image);
    assert(!patch_elf_soname(patch,real,0x1000));
    assert(ftruncate(real,12)==0);assert(!patch_elf_soname(patch,real,0));
    elf_image(image);write_image(real,image);
    driver_namespace=(void*)1;
    assert(!linker_ns_dlopen_unique(NULL,"libvulkan.so",RTLD_NOW));
    assert(!linker_ns_dlopen_unique(argv[1],"../libvulkan.so",RTLD_NOW));
    loader_failure=1;assert(!linker_ns_dlopen_unique(argv[1],"libvulkan.so",RTLD_NOW));
    assert(fcntl(last_loader_fd,F_GETFD)==-1&&errno==EBADF&&unique_loader_roots==NULL);
    loader_failure=0;void *handle=linker_ns_dlopen_unique(argv[1],"libvulkan.so",RTLD_NOW);assert(handle==(void*)0x1234);
    int retained=last_loader_fd;assert(unique_loader_roots&&unique_loader_roots->fd==retained);
    assert(fcntl(retained,F_GETFD)==FD_CLOEXEC);linker_ns_forget_unique(handle);
    assert(unique_loader_roots==NULL&&fcntl(retained,F_GETFD)==-1&&errno==EBADF);
    linker_ns_forget_unique(handle); /* idempotent rejected-root cleanup */
    assert(loader_opens==2);close(real);close(patch);unlink(realpath);unlink(patchpath);

    long page=sysconf(_SC_PAGESIZE);assert(page>0);
    unsigned char *code=__real_mmap(NULL,(size_t)page*2,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);assert(code!=MAP_FAILED);
    assert(mprotect(code+page,(size_t)page,PROT_NONE)==0);
    uint32_t *last=(uint32_t*)(code+page-4);*last=0;
    assert(find_branch_label(last)==NULL); /* Cannot cross the following PROT_NONE page. */
    assert(mprotect(code,(size_t)page,PROT_READ|PROT_WRITE)==0);*last=0x97ffffff;
    assert(find_branch_label(last)==(void*)((char*)last-4)); /* sign extension */
    assert(find_branch_label(NULL)==NULL&&find_branch_label((char*)last+1)==NULL);
    assert(__real_munmap(code,(size_t)page*2)==0);
    assert(consolidation_hook_dlopen("libfoo.so",RTLD_NOW,NULL)==NULL);
    assert(android_load_sphal_library(NULL,RTLD_NOW)==NULL);
    app__pojav_linkerhook_pass_handles((void*)8,(void*)private_ext,(void*)exported_namespace);
    assert(consolidation_hook_dlopen(NULL,RTLD_NOW,NULL)==NULL);
    assert(consolidation_hook_dlopen("vulkan.adreno.so",RTLD_NOW,NULL)==(void*)8);
    assert(android_load_sphal_library("vulkan.adreno.so",RTLD_NOW)==(void*)8&&!private_ext_calls);
    assert(android_load_sphal_library("libfoo.so",RTLD_NOW)==(void*)9);
    namespace_available=1;assert(android_load_sphal_library("libfoo.so",RTLD_NOW)==(void*)9&&private_ext_calls==2);
    puts("PASS production optional linkerhook: absent initialization/null filename fails closed, Vulkan driver root preserved, missing namespace fallback, fully initialized extension info");
    puts("PASS production optional-driver N04: MAP_FAILED, msync failure, 12 malformed ELF cases/no SONAME/short input, borrowed fd ownership, balanced mappings, unique loader fail/root retention/reject cleanup, bounded ARM64 BL scanner with signed offset and protected next page");
    return 0;
}
